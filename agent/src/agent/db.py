"""Shared asyncpg access under the tenant's row-level security context.

Every statement runs in a transaction that first sets ``app.tenant_id``, so
the policies that guard the server guard the agent, and the connecting role
is never a superuser.
"""

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import asyncpg
from pgvector.asyncpg import register_vector


class TenantPool:
    """Lazy connection pool whose transactions are scoped to one tenant."""

    def __init__(self, database_url: str) -> None:
        """Remember the URL. Nothing connects until the first transaction."""
        self._database_url = database_url
        self._pool: asyncpg.Pool | None = None
        self._pool_lock = asyncio.Lock()

    async def _get_pool(self) -> asyncpg.Pool:
        """Create the pool on first use, serialized by the lock."""
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

    @asynccontextmanager
    async def tenant_transaction(
        self, tenant_id: str
    ) -> AsyncIterator[asyncpg.Connection]:
        """Yield a connection inside a transaction scoped to the tenant."""
        pool = await self._get_pool()
        async with pool.acquire() as connection, connection.transaction():
            await connection.execute(
                "SELECT set_config('app.tenant_id', $1, true)", tenant_id
            )
            yield connection
