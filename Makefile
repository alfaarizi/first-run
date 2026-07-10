SHELL := /usr/bin/env bash -o errexit -o pipefail -o nounset

BUF := npx --no-install buf
SPECTRAL := npx --no-install spectral
GRAPHQL_INSPECTOR := npx --no-install graphql-inspector

.PHONY: up down tools seed generate generate-server generate-web test test-server test-agent \
	test-widget test-web eval e2e lint lint-api lint-server lint-agent lint-web size migrate \
	load replay rollback

# --wait returns once every healthcheck passes, so seed can run immediately.
up:
	@if [ ! -f .env ]; then echo "up: missing .env, run 'cp .env.example .env'" >&2; exit 1; fi
	docker compose up --detach --build --wait
	@echo ">> dashboard  http://localhost:5173"
	@echo ">> tasklet    http://localhost:5174"
	@echo ">> langfuse   http://localhost:3000"

# --profile tools so a console started by `make tools` comes down too.
down:
	docker compose --profile tools down

# Debug UIs on demand, kept out of `make up` and its memory budget. Naming the
# console service starts it plus its dependencies and no other profile service.
tools:
	docker compose up --detach --wait console
	@echo ">> console    http://localhost:8085"

seed:
	set -a; source ./.env; set +a; \
	docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
		-v sdk_key="$$VITE_FIRSTRUN_KEY" -v hmac_key="$$VITE_FIRSTRUN_HMAC_KEY" \
		-U postgres -d firstrun < scripts/seed.sql; \
	FIRSTRUN_SERVER_URL=http://localhost:8080 node scripts/seed-traffic.mjs
	@echo ">> seeded the demo tenant and replayed demo journeys"

# Regenerates every stub from /api.
generate: generate-server generate-web
	@if [ -f server/pom.xml ]; then \
		(cd server && ./mvnw -q generate-sources); \
	else echo ">> skipping Java stubs, server/ is not scaffolded"; fi
	@if [ -f agent/pyproject.toml ]; then \
		(cd agent && uv run python -m grpc_tools.protoc -I ../api/proto \
			--python_out=src --grpc_python_out=src --pyi_out=src \
			../api/proto/firstrun/v1/*.proto); \
	else echo ">> skipping Python stubs, agent/ is not scaffolded"; fi

# The SDL the server serves, copied from /api so tests never run a stale schema.
generate-server:
	@if [ -f server/pom.xml ]; then \
		mkdir -p server/src/main/resources/graphql && \
		cp api/graphql/*.graphqls server/src/main/resources/graphql/; \
	else echo ">> skipping the schema copy, server/ is not scaffolded"; fi

# The GraphQL client types the dashboard imports from src/gql, generated from /api.
generate-web:
	@if [ -f web/package.json ]; then \
		(cd web && npm run codegen); \
	else echo ">> skipping GraphQL types, web/ is not scaffolded"; fi

test: test-server test-agent test-widget test-web

# Needs the Docker daemon because Testcontainers starts throwaway Postgres and Redpanda.
test-server: generate-server
	cd server && ./mvnw -q verify

test-agent:
	cd agent && uv run pytest

test-widget:
	@if [ -f widget/package.json ]; then \
		(cd widget && npx vitest run); \
	else echo ">> skipping test-widget, widget/ is not scaffolded"; fi

# Needs the GraphQL client types, which the tests and the type check import from src/gql.
test-web: generate-web
	cd web && npm run test && npm run typecheck

# Verifies every pinned dataset: schema, row count, and SHA-256.
eval:
	@if [ -f evals/baselines.json ]; then \
		node scripts/verify-datasets.mjs; \
	else echo ">> skipping eval, evals/ is not scaffolded"; fi

# Needs the compose stack up and Playwright browsers installed once:
# (cd tasklet && npx playwright install chromium)
e2e:
	cd tasklet && npx playwright test

lint: lint-api lint-server lint-agent lint-web

# The GraphQL self-diff never reports changes. It proves the SDL files parse
# and compose, and CI diffs against the PR base branch.
lint-api:
	cd api && $(BUF) lint && $(BUF) format --diff --exit-code
	$(GRAPHQL_INSPECTOR) diff "api/graphql/*.graphqls" "api/graphql/*.graphqls"
	$(SPECTRAL) lint --ruleset api/.spectral.yaml --fail-severity warn "api/openapi/*.yaml"

lint-server:
	cd server && ./mvnw -q spotless:check

lint-agent:
	cd agent && uv run ruff check && uv run ruff format --check && uv run mypy .

lint-web:
	cd web && npm run lint

size:
	@if [ -f widget/package.json ]; then \
		(cd widget && npm run size); \
	else echo ">> skipping size, widget/ is not scaffolded"; fi

migrate:
	set -a; source ./.env; set +a; cd server && ./mvnw -q flyway:migrate

load:
	@echo ">> skipping load, the k6 script arrives with infra/"

# Replays a dead-letter topic to its source after a handler fix, bounded per partition to the
# records present now and trimmed only once its pipe succeeds, so a failed run stays rerunnable
# and a rerun re-emits nothing (handler dedupe absorbs redeliveries for 24 hours). A replicated
# topic prints REPLICAS as a space-bearing list, stripped before the positional read, and keys
# and values move hex-encoded so a tab or newline never collides with the pipe delimiters.
replay:
	@if [ -z "$(TOPIC)" ]; then echo "replay: set TOPIC, e.g. make replay TOPIC=events.raw" >&2; exit 1; fi
	docker compose exec -T redpanda bash -o errexit -o pipefail -o nounset -c 'dlq=$(TOPIC).dlq; \
		rows=$$(rpk topic describe $$dlq -p | sed -E "1d; s/\[[^]]*\] *//"); \
		[ -n "$$rows" ] || { echo ">> $$dlq does not exist" >&2; exit 1; }; \
		echo "$$rows" | while read -r part _ _ start hwm _; do \
			if [ "$$hwm" -gt "$$start" ]; then \
				rpk topic consume $$dlq --partitions $$part --format "%k{hex}\t%v{hex}\n" --offset :$$hwm \
					| rpk topic produce $(TOPIC) --format "%k{hex}\t%v{hex}\n"; \
				rpk topic trim-prefix $$dlq --partitions $$part --offset $$hwm --no-confirm; \
			else echo ">> $$dlq/$$part has no records to replay"; fi; \
		done'

rollback:
	@echo ">> skipping rollback, no deploy pipeline exists yet"
