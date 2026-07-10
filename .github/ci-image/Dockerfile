# CI toolchain for the make legs: JDK 21 base, Node from the official image,
# uv from its distroless image, and dependency caches warmed from the
# lockfiles. Cache paths live under /opt/cache because GitHub container jobs
# override HOME, which would orphan caches stored under the build-time home.
FROM eclipse-temurin:21-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git make \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node:22-bookworm-slim /usr/local /usr/local
COPY --from=ghcr.io/astral-sh/uv:0.11.26 /uv /uvx /usr/local/bin/

ENV NPM_CONFIG_CACHE=/opt/cache/npm \
    UV_CACHE_DIR=/opt/cache/uv \
    MAVEN_USER_HOME=/opt/cache/maven \
    MAVEN_OPTS=-Dmaven.repo.local=/opt/cache/m2

COPY package.json package-lock.json /warm/
COPY web/package.json web/package-lock.json /warm/web/
COPY agent/pyproject.toml agent/uv.lock /warm/agent/
COPY server/pom.xml server/mvnw /warm/server/
COPY server/.mvn /warm/server/.mvn

# npm ci and uv sync fill the caches. Each layer discards the installed
# trees, because every job runs its own install against a fresh checkout.
RUN cd /warm && npm ci --no-audit --no-fund \
    && cd web && npm ci --no-audit --no-fund \
    && rm -rf /warm/node_modules /warm/web/node_modules
RUN cd /warm/agent && uv sync --frozen --no-install-project && rm -rf /warm/agent/.venv
RUN cd /warm/server && ./mvnw -q -B dependency:go-offline && rm -rf /warm
