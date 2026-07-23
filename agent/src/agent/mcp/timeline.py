"""Reads an end user's milestone progress under the tenant's row-level security."""

from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from agent.db import TenantPool


@dataclass(frozen=True)
class MilestoneProgress:
    """One milestone's progress for the end user."""

    milestone_name: str
    state: Literal["IN_PROGRESS", "COMPLETED"]
    started_at: datetime
    completed_at: datetime | None


class TimelineReader:
    """asyncpg-backed reader for an end user's milestone progress."""

    def __init__(self, database_url: str) -> None:
        """Open a lazy tenant-scoped pool against the database."""
        self._db = TenantPool(database_url)

    async def close(self) -> None:
        """Release the pool at shutdown."""
        await self._db.close()

    async def read(
        self, *, tenant_id: str, app_id: str, end_user_hash: str
    ) -> list[MilestoneProgress]:
        """Return the end user's milestone progress, oldest first."""
        async with self._db.tenant_transaction(tenant_id) as connection:
            rows = await connection.fetch(
                """
                SELECT m.name, p.state, p.started_at, p.completed_at
                FROM milestone_progress p
                JOIN end_user u ON u.id = p.end_user_id
                JOIN milestone m ON m.id = p.milestone_id
                WHERE u.app_id = $1 AND u.external_hash = $2
                ORDER BY p.started_at
                """,
                app_id,
                end_user_hash,
            )
        return [
            MilestoneProgress(
                milestone_name=row["name"],
                state=row["state"],
                started_at=row["started_at"],
                completed_at=row["completed_at"],
            )
            for row in rows
        ]
