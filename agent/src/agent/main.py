"""FastAPI entrypoint for health and admin endpoints."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from agent.config import get_settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Fail fast at startup when Langfuse configuration is missing."""
    get_settings()
    yield


app = FastAPI(title="firstrun-agent", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    """Report liveness for the compose healthcheck."""
    return {"status": "ok"}
