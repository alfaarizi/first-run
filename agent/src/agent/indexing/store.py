"""Writes doc sources and chunks under the tenant's row-level security context.

Every statement runs in a transaction that first sets ``app.tenant_id``, so
the policies that guard the server guard the agent, and the connecting role
is never a superuser.
"""

import asyncio
from collections.abc import Sequence
from dataclasses import dataclass

import asyncpg
from pgvector import Vector
from pgvector.asyncpg import register_vector


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
        self._database_url = database_url
        self._pool: asyncpg.Pool | None = None
        self._pool_lock = asyncio.Lock()

    async def _get_pool(self) -> asyncpg.Pool:
        # Lazy so the service starts, and stays honest on /health, before the
        # database is reachable.
        async with self._pool_lock:
            if self._pool is None:
                self._pool = await asyncpg.create_pool(
                    self._database_url, init=register_vector
                )
            return self._pool

    async def close(self) -> None:
        """Release the pool at shutdown."""
        if self._pool is not None:
            await self._pool.close()

    async def mark_indexing(
        self, *, tenant_id: str, app_id: str, source_id: str, url: str
    ) -> None:
        """Record the source as INDEXING, creating its row on first crawl."""
        pool = await self._get_pool()
        async with pool.acquire() as connection, connection.transaction():
            await _set_tenant(connection, tenant_id)
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

    async def write_chunks(
        self,
        *,
        tenant_id: str,
        source_id: str,
        crawl_id: str,
        rows: Sequence[ChunkRow],
    ) -> None:
        """Insert one page's chunks, searchable as soon as they commit."""
        pool = await self._get_pool()
        async with pool.acquire() as connection, connection.transaction():
            await _set_tenant(connection, tenant_id)
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
        pool = await self._get_pool()
        async with pool.acquire() as connection, connection.transaction():
            await _set_tenant(connection, tenant_id)
            await connection.execute(
                "DELETE FROM doc_chunk WHERE source_id = $1 AND crawl_id <> $2",
                source_id,
                crawl_id,
            )
            await connection.execute(
                """
                UPDATE doc_source SET status = 'READY', last_indexed_at = now()
                WHERE id = $1
                """,
                source_id,
            )

    async def fail(self, *, tenant_id: str, source_id: str, crawl_id: str) -> None:
        """Discard the crawl's own chunks and mark the source FAILED.

        The previous crawl's chunks stay live.
        """
        pool = await self._get_pool()
        async with pool.acquire() as connection, connection.transaction():
            await _set_tenant(connection, tenant_id)
            await connection.execute(
                "DELETE FROM doc_chunk WHERE source_id = $1 AND crawl_id = $2",
                source_id,
                crawl_id,
            )
            await connection.execute(
                "UPDATE doc_source SET status = 'FAILED' WHERE id = $1",
                source_id,
            )


async def _set_tenant(connection: asyncpg.Connection, tenant_id: str) -> None:
    await connection.execute("SELECT set_config('app.tenant_id', $1, true)", tenant_id)
