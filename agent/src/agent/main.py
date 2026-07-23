"""FastAPI and grpc.aio entrypoint."""

import logging
from collections.abc import AsyncIterator
from contextlib import AsyncExitStack, asynccontextmanager

import grpc
from fastapi import FastAPI
from starlette.routing import Mount

from agent.config import get_settings
from agent.graph.build import build_graph
from agent.graph.service import ConversationService
from agent.indexing.crawler import Crawler
from agent.indexing.indexer import Indexer
from agent.indexing.service import KnowledgeService
from agent.indexing.store import ChunkStore
from agent.llm.client import ChatClient, EmbeddingClient
from agent.mcp.server import build_mcp_server
from agent.mcp.timeline import TimelineReader
from agent.retrieval.search import ChunkSearcher
from agent.tracing import build_tracer
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
    embedder = EmbeddingClient()
    indexer = Indexer(
        crawler=Crawler(
            max_pages=settings.crawl_max_pages,
            timeout_seconds=settings.crawl_timeout_seconds,
            deadline_seconds=settings.crawl_deadline_seconds,
            max_response_bytes=settings.crawl_max_response_bytes,
            allow_local=settings.crawl_allow_local,
        ),
        embedder=embedder,
        store=store,
        chunk_max_chars=settings.chunk_max_chars,
        crawl_max_concurrent=settings.crawl_max_concurrent,
    )
    langfuse = build_tracer(
        public_key=settings.langfuse_public_key,
        secret_key=settings.langfuse_secret_key,
        host=settings.langfuse_host,
    )
    searcher = ChunkSearcher(settings.database_url)
    timeline = TimelineReader(settings.database_url)
    graph = build_graph(
        embedder=embedder,
        searcher=searcher,
        chat=ChatClient(),
        langfuse=langfuse,
        answer_model=settings.answer_model,
        top_k=settings.retrieval_top_k,
    )

    mcp = None
    # One pin set without the other must fail loudly in build_mcp_server
    # rather than silently leave /mcp unmounted.
    if settings.mcp_tenant_id or settings.mcp_app_id:
        mcp = build_mcp_server(
            tenant_id=settings.mcp_tenant_id,
            app_id=settings.mcp_app_id,
            embedder=embedder,
            searcher=searcher,
            timeline=timeline,
            langfuse=langfuse,
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

    # The MCP app closes over clients built in this lifespan, so its route
    # mounts here and leaves with it, serving /mcp beside /health. Shutdown
    # unwinds in reverse: the route unmounts before its session manager stops.
    async with AsyncExitStack() as stack:
        if mcp is not None:
            mcp_route = Mount("/", mcp.streamable_http_app())
            await stack.enter_async_context(mcp.session_manager.run())
            app.router.routes.append(mcp_route)
            stack.callback(app.router.routes.remove, mcp_route)
        yield
    await server.stop(grace=_STOP_GRACE_SECONDS)

    # Crawls outlive their RPCs. Clean up before the store closes.
    await indexer.close()
    await searcher.close()
    await timeline.close()
    await store.close()
    langfuse.shutdown()


app = FastAPI(title="firstrun-agent", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    """Report liveness for the compose healthcheck."""
    return {"status": "ok"}
