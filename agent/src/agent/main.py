"""FastAPI entrypoint for health and admin endpoints."""

import logging

from fastapi import FastAPI

from agent.config import settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

app = FastAPI(title=settings.app_name)


@app.get("/health")
def health() -> dict[str, str]:
    """Report liveness for the compose healthcheck."""
    return {"status": "ok"}
