"""Searches doc chunks under the tenant's row-level security context."""

from dataclasses import dataclass

from pgvector import Vector

from agent.db import TenantPool


@dataclass(frozen=True)
class RetrievedChunk:
    """One chunk pulled back for grounding, with its citation fields."""

    source_url: str
    heading_path: tuple[str, ...]
    content: str


class ChunkSearcher:
    """asyncpg-backed nearest-neighbor reader for ``doc_chunk``."""

    def __init__(self, database_url: str) -> None:
        """Open a lazy tenant-scoped pool against the database."""
        self._db = TenantPool(database_url)

    async def close(self) -> None:
        """Release the pool at shutdown."""
        await self._db.close()

    async def search(
        self, *, tenant_id: str, app_id: str, embedding: list[float], limit: int
    ) -> list[RetrievedChunk]:
        """Return the chunks nearest the query embedding for one app."""
        async with self._db.tenant_transaction(tenant_id) as connection:
            # Row-level security and the app join filter after the index
            # scan, so iterative scans keep recall for small tenants.
            await connection.execute("SET LOCAL hnsw.iterative_scan = relaxed_order")
            rows = await connection.fetch(
                """
                SELECT c.source_url, c.heading_path, c.content
                FROM doc_chunk c
                JOIN doc_source s ON s.id = c.source_id
                WHERE s.app_id = $2
                ORDER BY c.embedding <#> $1
                LIMIT $3
                """,
                Vector(embedding),
                app_id,
                limit,
            )
        return [
            RetrievedChunk(
                source_url=row["source_url"],
                heading_path=tuple(row["heading_path"]),
                content=row["content"],
            )
            for row in rows
        ]
