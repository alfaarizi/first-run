"""Writes doc sources and chunks under the tenant's row-level security context."""

from collections.abc import Sequence
from dataclasses import dataclass

from pgvector import Vector

from agent.db import TenantPool


@dataclass(frozen=True)
class ChunkRow:
    """One embedded chunk ready to insert."""

    id: str
    source_url: str
    heading_path: tuple[str, ...]
    content: str
    embedding: list[float]


class ChunkStore:
    """asyncpg-backed writer for ``doc_source`` and ``doc_chunk``."""

    def __init__(self, database_url: str) -> None:
        """Open a lazy tenant-scoped pool against the database."""
        self._db = TenantPool(database_url)

    async def close(self) -> None:
        """Release the pool at shutdown."""
        await self._db.close()

    async def mark_indexing(
        self, *, tenant_id: str, app_id: str, source_id: str, url: str
    ) -> None:
        """Record the source as INDEXING and sweep chunks of dead crawls.

        This is the recovery point for rows a killed agent left behind.
        """
        async with self._db.tenant_transaction(tenant_id) as connection:
            await connection.execute(
                """
                INSERT INTO doc_source (id, tenant_id, app_id, url, status)
                VALUES ($1, $2, $3, $4, 'INDEXING')
                ON CONFLICT (id)
                    DO UPDATE SET status = 'INDEXING', url = EXCLUDED.url
                """,
                source_id,
                tenant_id,
                app_id,
                url,
            )
            await connection.execute(
                """
                DELETE FROM doc_chunk
                WHERE source_id = $1 AND crawl_id IS DISTINCT FROM
                    (SELECT live_crawl_id FROM doc_source WHERE id = $1)
                """,
                source_id,
            )

    async def write_chunks(
        self,
        *,
        tenant_id: str,
        source_id: str,
        crawl_id: str,
        rows: Sequence[ChunkRow],
    ) -> None:
        """Insert one page's chunks, searchable as soon as they commit."""
        async with self._db.tenant_transaction(tenant_id) as connection:
            await connection.executemany(
                """
                INSERT INTO doc_chunk
                    (id, tenant_id, source_id, crawl_id, source_url,
                     heading_path, content, embedding)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                """,
                [
                    (
                        row.id,
                        tenant_id,
                        source_id,
                        crawl_id,
                        row.source_url,
                        list(row.heading_path),
                        row.content,
                        Vector(row.embedding),
                    )
                    for row in rows
                ],
            )

    async def complete(self, *, tenant_id: str, source_id: str, crawl_id: str) -> None:
        """Sweep every older crawl's chunks and mark the source READY."""
        async with self._db.tenant_transaction(tenant_id) as connection:
            await connection.execute(
                "DELETE FROM doc_chunk WHERE source_id = $1 AND crawl_id <> $2",
                source_id,
                crawl_id,
            )
            await connection.execute(
                """
                UPDATE doc_source
                SET status = 'READY', last_indexed_at = now(), live_crawl_id = $2
                WHERE id = $1
                """,
                source_id,
                crawl_id,
            )

    async def fail(self, *, tenant_id: str, source_id: str, crawl_id: str) -> None:
        """Sweep the failed crawl's own chunks and mark the source FAILED."""
        async with self._db.tenant_transaction(tenant_id) as connection:
            await connection.execute(
                "DELETE FROM doc_chunk WHERE source_id = $1 AND crawl_id = $2",
                source_id,
                crawl_id,
            )
            await connection.execute(
                "UPDATE doc_source SET status = 'FAILED' WHERE id = $1",
                source_id,
            )
