# Installs the dependency layer, then the project layer, both against the
# lockfile, per uv's Docker guide (docs.astral.sh/uv/guides/integration/docker).
FROM ghcr.io/astral-sh/uv:python3.12-alpine AS build
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy
WORKDIR /app
# Dev group included so grpcio-tools is on hand for the stub generation below.
RUN --mount=type=cache,target=/root/.cache/uv \
    --mount=type=bind,source=uv.lock,target=uv.lock \
    --mount=type=bind,source=pyproject.toml,target=pyproject.toml \
    uv sync --locked --no-install-project
COPY . /app
# Generates the gRPC stubs from the proto contract, passed as the `proto`
# build context, so the image never depends on a host-side prebuild.
RUN --mount=type=bind,from=proto,target=/proto \
    .venv/bin/python -m grpc_tools.protoc -I /proto \
    --python_out=src --grpc_python_out=src --pyi_out=src \
    /proto/firstrun/v1/*.proto
# Installs the project and prunes the dev group for a lean runtime venv.
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev

# Runtime carries only the venv on a clean alpine Python: no uv, no build tools.
FROM python:3.12-alpine
WORKDIR /app
COPY --from=build /app /app
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000 50051
CMD ["uvicorn", "agent.main:app", "--host", "0.0.0.0", "--port", "8000"]
