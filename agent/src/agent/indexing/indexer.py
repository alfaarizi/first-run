"""Runs one crawl per doc source and replaces the source's index on completion."""

import asyncio
import logging
from collections.abc import AsyncIterator, Sequence
from typing import Protocol

from uuid6 import uuid7

from agent.indexing.chunker import chunk_page
from agent.indexing.crawler import Page
from agent.indexing.store import ChunkRow

logger = logging.getLogger(__name__)


class PageSource(Protocol):
    """The slice of the crawler the indexer needs."""

    def crawl(self, root_url: str) -> AsyncIterator[Page]: ...


class Embedder(Protocol):
    """The slice of the LLM client the indexer needs."""

    async def embed_documents(self, texts: list[str]) -> list[list[float]]: ...


class ChunkWriter(Protocol):
    """The slice of the store the indexer needs."""

    async def mark_indexing(
        self, *, tenant_id: str, app_id: str, source_id: str, url: str
    ) -> None: ...

    async def write_chunks(
        self,
        *,
        tenant_id: str,
        source_id: str,
        crawl_id: str,
        rows: Sequence[ChunkRow],
    ) -> None: ...

    async def complete(
        self, *, tenant_id: str, source_id: str, crawl_id: str
    ) -> None: ...

    async def fail(self, *, tenant_id: str, source_id: str, crawl_id: str) -> None: ...


class Indexer:
    """Owns the per-source crawl tasks behind ``KnowledgeService.Reindex``."""

    def __init__(
        self,
        *,
        crawler: PageSource,
        embedder: Embedder,
        store: ChunkWriter,
        chunk_max_chars: int,
    ) -> None:
        self._crawler = crawler
        self._embedder = embedder
        self._store = store
        self._chunk_max_chars = chunk_max_chars
        self._running: dict[str, asyncio.Task[None]] = {}

    def start(
        self, *, tenant_id: str, app_id: str, source_id: str, source_url: str
    ) -> bool:
        """Start a crawl for the source, or refuse while one is running."""
        if source_id in self._running:
            return False
        task = asyncio.create_task(
            self._run(
                tenant_id=tenant_id,
                app_id=app_id,
                source_id=source_id,
                source_url=source_url,
            )
        )
        self._running[source_id] = task
        task.add_done_callback(lambda _: self._running.pop(source_id, None))
        return True

    async def _run(
        self, *, tenant_id: str, app_id: str, source_id: str, source_url: str
    ) -> None:
        crawl_id = str(uuid7())
        chunk_total = 0
        try:
            await self._store.mark_indexing(
                tenant_id=tenant_id, app_id=app_id, source_id=source_id, url=source_url
            )
            async for page in self._crawler.crawl(source_url):
                chunks = chunk_page(
                    page.url, page.html, max_chars=self._chunk_max_chars
                )
                if not chunks:
                    continue
                embeddings = await self._embedder.embed_documents(
                    [
                        "\n".join((*chunk.heading_path, chunk.content))
                        for chunk in chunks
                    ]
                )
                rows = [
                    ChunkRow(
                        id=str(uuid7()),
                        source_url=chunk.source_url,
                        heading_path=chunk.heading_path,
                        content=chunk.content,
                        embedding=embedding,
                    )
                    for chunk, embedding in zip(chunks, embeddings, strict=True)
                ]
                await self._store.write_chunks(
                    tenant_id=tenant_id,
                    source_id=source_id,
                    crawl_id=crawl_id,
                    rows=rows,
                )
                chunk_total += len(rows)
            if chunk_total == 0:
                # Sweeping on an empty crawl would erase a working index over
                # a transient outage or a robots change.
                logger.warning("crawl of %s wrote no chunks", source_url)
                await self._fail(tenant_id, source_id, crawl_id)
                return
            await self._store.complete(
                tenant_id=tenant_id, source_id=source_id, crawl_id=crawl_id
            )
            logger.info("reindexed source %s from %s", source_id, source_url)
        except asyncio.CancelledError:
            # Shutdown cancels the task mid-crawl. Clean up, then let the
            # cancellation propagate.
            await self._fail(tenant_id, source_id, crawl_id)
            raise
        except Exception:
            logger.exception("reindex failed for source %s", source_id)
            await self._fail(tenant_id, source_id, crawl_id)

    async def _fail(self, tenant_id: str, source_id: str, crawl_id: str) -> None:
        try:
            await self._store.fail(
                tenant_id=tenant_id, source_id=source_id, crawl_id=crawl_id
            )
        except Exception:
            logger.exception("could not mark source %s FAILED", source_id)
