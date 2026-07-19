"""FastAPI and grpc.aio entrypoint."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import grpc
from fastapi import FastAPI

from agent.config import get_settings
from agent.indexing.crawler import Crawler
from agent.indexing.indexer import Indexer
from agent.indexing.service import KnowledgeService
from agent.indexing.store import ChunkStore
from agent.llm.client import EmbeddingClient
from firstrun.v1 import knowledge_pb2_grpc

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

_STOP_GRACE_SECONDS = 5


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Fail fast on missing configuration, then serve gRPC beside HTTP."""
    settings = get_settings()
    store = ChunkStore(settings.database_url)
    indexer = Indexer(
        crawler=Crawler(
            max_pages=settings.crawl_max_pages,
            timeout_seconds=settings.crawl_timeout_seconds,
            deadline_seconds=settings.crawl_deadline_seconds,
            max_response_bytes=settings.crawl_max_response_bytes,
        ),
        embedder=EmbeddingClient(),
        store=store,
        chunk_max_chars=settings.chunk_max_chars,
        crawl_max_concurrent=settings.crawl_max_concurrent,
    )
    server = grpc.aio.server()
    knowledge_pb2_grpc.add_KnowledgeServiceServicer_to_server(
        KnowledgeService(indexer), server
    )

    if server.add_insecure_port(f"[::]:{settings.grpc_port}") == 0:
        raise RuntimeError(f"gRPC port {settings.grpc_port} did not bind")
    await server.start()
    yield
    await server.stop(grace=_STOP_GRACE_SECONDS)

    # Crawls outlive their RPCs. Clean up before the store closes.
    await indexer.close()

    await store.close()


app = FastAPI(title="firstrun-agent", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    """Report liveness for the compose healthcheck."""
    return {"status": "ok"}
