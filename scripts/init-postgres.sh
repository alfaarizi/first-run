#!/usr/bin/env bash
# Runs once on first boot of the postgres container through the image's
# /docker-entrypoint-initdb.d hook.
#
# The server connects as `firstrun`, a plain login role, never a superuser
# or BYPASSRLS role. Langfuse gets its own role and database, and the
# superuser creates pgvector so migrations never need elevated rights.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-SQL
	CREATE ROLE ${POSTGRES_APP_USER} LOGIN PASSWORD '${POSTGRES_APP_PASSWORD}';
	CREATE ROLE langfuse LOGIN PASSWORD 'langfuse';
	CREATE DATABASE firstrun OWNER ${POSTGRES_APP_USER};
	CREATE DATABASE langfuse OWNER langfuse;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname firstrun \
  -c 'CREATE EXTENSION IF NOT EXISTS vector;'
