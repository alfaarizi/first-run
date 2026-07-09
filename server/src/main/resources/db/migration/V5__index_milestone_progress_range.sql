-- The funnel read joins milestone_progress by milestone_id over a started_at
-- range on every dashboard poll. The primary key leads with end_user_id, so
-- Postgres cannot seek that window without this index.

CREATE INDEX milestone_progress_milestone_id_started_at_idx
    ON milestone_progress (milestone_id, started_at);
