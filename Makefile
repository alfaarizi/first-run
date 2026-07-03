SHELL := /usr/bin/env bash -o errexit -o pipefail -o nounset

BUF := npx --no-install buf
SPECTRAL := npx --no-install spectral
GRAPHQL_INSPECTOR := npx --no-install graphql-inspector

.PHONY: generate lint

# Regenerates every stub from /api.
generate:
	@if [ -f server/pom.xml ]; then \
		mkdir -p server/src/main/resources/graphql && \
		cp api/graphql/*.graphqls server/src/main/resources/graphql/ && \
		(cd server && ./mvnw -q generate-sources); \
	else echo "generate: server/ not scaffolded, skipping Java stubs and schema copy"; fi
	@if [ -f agent/pyproject.toml ]; then \
		(cd agent && uv run python -m grpc_tools.protoc -I ../api/proto \
			--python_out=src --grpc_python_out=src --pyi_out=src \
			../api/proto/firstrun/v1/*.proto); \
	else echo "generate: agent/ not scaffolded, skipping Python stubs"; fi
	@if [ -f web/package.json ]; then \
		(cd web && npm run codegen); \
	else echo "generate: web/ not scaffolded, skipping GraphQL types"; fi

# The GraphQL self-diff never reports changes. It proves the SDL files parse
# and compose, and CI diffs against the PR base branch.
lint:
	cd api && $(BUF) lint && $(BUF) format --diff --exit-code
	$(GRAPHQL_INSPECTOR) diff "api/graphql/*.graphqls" "api/graphql/*.graphqls"
	$(SPECTRAL) lint --ruleset api/.spectral.yaml --fail-severity warn "api/openapi/*.yaml"
