"""FastAPI and grpc.aio entrypoint."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import grpc
from fastapi import FastAPI
from langfuse import Langfuse

from agent.config import get_settings
from agent.graph.build import build_graph
from agent.graph.service import ConversationService
from agent.indexing.crawler import Crawler
from agent.indexing.indexer import Indexer
from agent.indexing.service import KnowledgeService
from agent.indexing.store import ChunkStore
from agent.llm.client import ChatClient, EmbeddingClient
from agent.retrieval.search import ChunkSearcher
from firstrun.v1 import conversation_pb2_grpc, knowledge_pb2_grpc

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
            allow_local=settings.crawl_allow_local,
        ),
        embedder=EmbeddingClient(),
        store=store,
        chunk_max_chars=settings.chunk_max_chars,
        crawl_max_concurrent=settings.crawl_max_concurrent,
    )
    langfuse = Langfuse(
        public_key=settings.langfuse_public_key,
        secret_key=settings.langfuse_secret_key,
        host=settings.langfuse_host,
    )
    searcher = ChunkSearcher(settings.database_url)
    graph = build_graph(
        embedder=EmbeddingClient(),
        searcher=searcher,
        chat=ChatClient(),
        langfuse=langfuse,
        answer_model=settings.answer_model,
        top_k=settings.retrieval_top_k,
    )

    server = grpc.aio.server()
    knowledge_pb2_grpc.add_KnowledgeServiceServicer_to_server(
        KnowledgeService(indexer), server
    )
    conversation_pb2_grpc.add_ConversationServiceServicer_to_server(
        ConversationService(graph, langfuse, settings.conversation_max_turns),
        server,
    )

    if server.add_insecure_port(f"[::]:{settings.grpc_port}") == 0:
        raise RuntimeError(f"gRPC port {settings.grpc_port} did not bind")
    await server.start()
    yield
    await server.stop(grace=_STOP_GRACE_SECONDS)

    # Crawls outlive their RPCs. Clean up before the store closes.
    await indexer.close()
    await searcher.close()
    await store.close()
    langfuse.shutdown()


app = FastAPI(title="firstrun-agent", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    """Report liveness for the compose healthcheck."""
    return {"status": "ok"}
