"""Searches doc chunks under the tenant's row-level security context.

Every query runs in a transaction that first sets ``app.tenant_id``, the same
guard the writer side uses, and the connecting role is never a superuser.
"""

import asyncio
from dataclasses import dataclass

import asyncpg
from pgvector import Vector
from pgvector.asyncpg import register_vector


@dataclass(frozen=True)
class RetrievedChunk:
    """One chunk pulled back for grounding, with its citation fields."""

    source_url: str
    heading_path: tuple[str, ...]
    content: str


class ChunkSearcher:
    """asyncpg-backed nearest-neighbor reader for ``doc_chunk``."""

    def __init__(self, database_url: str) -> None:
        self._database_url = database_url
        self._pool: asyncpg.Pool | None = None
        self._pool_lock = asyncio.Lock()

    async def _get_pool(self) -> asyncpg.Pool:
        # Lazy so the service starts, and stays honest on /health, before the
        # database is reachable.
        async with self._pool_lock:
            if self._pool is None:
                # min_size 1: two agent pools share this database, so the
                # idle floor stays one connection each.
                self._pool = await asyncpg.create_pool(
                    self._database_url, init=register_vector, min_size=1
                )
            return self._pool

    async def close(self) -> None:
        """Release the pool at shutdown."""
        if self._pool is not None:
            await self._pool.close()

    async def search(
        self, *, tenant_id: str, app_id: str, embedding: list[float], limit: int
    ) -> list[RetrievedChunk]:
        """Return the chunks nearest the query embedding for one app."""
        pool = await self._get_pool()
        async with pool.acquire() as connection, connection.transaction():
            await connection.execute(
                "SELECT set_config('app.tenant_id', $1, true)", tenant_id
            )
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
