-- Runs once at container start as the bootstrap superuser, mirroring
-- scripts/init-postgres.sh, so migrations never need elevated rights.
CREATE EXTENSION IF NOT EXISTS vector;
