"""Reindex accepts one crawl per source and replaces the index on completion."""

import asyncio
from collections.abc import AsyncIterator, Sequence
from typing import cast

import grpc
import pytest

from agent.indexing.crawler import Page
from agent.indexing.indexer import Indexer
from agent.indexing.service import KnowledgeService
from agent.indexing.store import ChunkRow
from firstrun.v1 import knowledge_pb2

_TENANT = "019813f2-0000-7000-8000-0000000000f1"
_APP = "019813f2-0000-7000-8000-0000000000f2"
_SOURCE = "019813f2-0000-7000-8000-0000000000f3"
_PAGE = Page(
    url="https://docs.example.com/guide",
    html="<title>T</title><body><h2 id='a'>Guide</h2><p>Some text.</p></body>",
)


class FakeCrawler:
    def __init__(
        self,
        gate: asyncio.Event | None = None,
        broken: bool = False,
        pages: list[Page] | None = None,
    ) -> None:
        self._gate = gate
        self._broken = broken
        self._pages = [_PAGE] if pages is None else pages

    async def crawl(self, root_url: str) -> AsyncIterator[Page]:
        if self._gate is not None:
            await self._gate.wait()
        for page in self._pages:
            yield page
        if self._broken:
            raise RuntimeError("crawl broke")


class FakeEmbedder:
    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [[0.0] * 4 for _ in texts]


class FakeStore:
    def __init__(self) -> None:
        self.events: list[str] = []
        self.rows: list[ChunkRow] = []
        self.write_crawl_id = ""
        self.fail_crawl_id = ""
        self.done = asyncio.Event()

    async def mark_indexing(
        self, *, tenant_id: str, app_id: str, source_id: str, url: str
    ) -> None:
        self.events.append("indexing")

    async def write_chunks(
        self,
        *,
        tenant_id: str,
        source_id: str,
        crawl_id: str,
        rows: Sequence[ChunkRow],
    ) -> None:
        self.events.append("write")
        self.rows.extend(rows)
        self.write_crawl_id = crawl_id

    async def complete(self, *, tenant_id: str, source_id: str, crawl_id: str) -> None:
        self.events.append(f"complete:{crawl_id}")
        self.done.set()

    async def fail(self, *, tenant_id: str, source_id: str, crawl_id: str) -> None:
        self.events.append("fail")
        self.fail_crawl_id = crawl_id
        self.done.set()


class AbortRaised(Exception):
    pass


class FakeContext:
    async def abort(self, code: grpc.StatusCode, details: str) -> None:
        raise AbortRaised(f"{code}: {details}")


def _service(store: FakeStore, crawler: FakeCrawler) -> KnowledgeService:
    indexer = Indexer(
        crawler=crawler,
        embedder=FakeEmbedder(),
        store=store,
        chunk_max_chars=2_000,
        crawl_max_concurrent=2,
    )
    return KnowledgeService(indexer)


def _request(
    source_url: str = "https://docs.example.com/",
) -> knowledge_pb2.ReindexRequest:
    return knowledge_pb2.ReindexRequest(
        tenant_id=_TENANT, app_id=_APP, source_id=_SOURCE, source_url=source_url
    )


def _context() -> grpc.aio.ServicerContext:
    return cast(grpc.aio.ServicerContext, FakeContext())


async def _settled(store: FakeStore) -> None:
    await asyncio.wait_for(store.done.wait(), timeout=1)
    for _ in range(10):
        await asyncio.sleep(0)


async def test_reindex_accepts_and_refuses_a_concurrent_recrawl() -> None:
    gate = asyncio.Event()
    store = FakeStore()
    service = _service(store, FakeCrawler(gate=gate))

    first = await service.Reindex(_request(), _context())
    second = await service.Reindex(_request(), _context())
    assert first.status == knowledge_pb2.REINDEX_STATUS_ACCEPTED
    assert first.source_id == _SOURCE
    assert second.status == knowledge_pb2.REINDEX_STATUS_ALREADY_RUNNING

    gate.set()
    await _settled(store)
    third = await service.Reindex(_request(), _context())
    assert third.status == knowledge_pb2.REINDEX_STATUS_ACCEPTED


async def test_completed_crawl_writes_chunks_then_sweeps_older_generations() -> None:
    store = FakeStore()
    service = _service(store, FakeCrawler())

    await service.Reindex(_request(), _context())
    await _settled(store)

    assert store.events == ["indexing", "write", f"complete:{store.write_crawl_id}"]
    assert store.rows
    assert store.rows[0].heading_path == ("T", "Guide")
    assert store.rows[0].source_url == "https://docs.example.com/guide#a"


async def test_failed_crawl_discards_its_own_chunks_and_keeps_the_old_index() -> None:
    store = FakeStore()
    service = _service(store, FakeCrawler(broken=True))

    await service.Reindex(_request(), _context())
    await _settled(store)

    assert store.events == ["indexing", "write", "fail"]
    assert store.fail_crawl_id == store.write_crawl_id


async def test_cancelled_crawl_discards_its_own_chunks_before_propagating() -> None:
    gate = asyncio.Event()
    store = FakeStore()
    indexer = Indexer(
        crawler=FakeCrawler(gate=gate),
        embedder=FakeEmbedder(),
        store=store,
        chunk_max_chars=2_000,
        crawl_max_concurrent=2,
    )
    indexer.start(
        tenant_id=_TENANT,
        app_id=_APP,
        source_id=_SOURCE,
        source_url="https://d.example",
    )
    task = indexer._running[_SOURCE]
    await asyncio.sleep(0)

    task.cancel()
    await _settled(store)

    assert task.cancelled()
    assert store.events == ["indexing", "fail"]


async def test_close_cancels_running_crawls_and_runs_their_cleanup() -> None:
    gate = asyncio.Event()
    store = FakeStore()
    indexer = Indexer(
        crawler=FakeCrawler(gate=gate),
        embedder=FakeEmbedder(),
        store=store,
        chunk_max_chars=2_000,
        crawl_max_concurrent=2,
    )
    indexer.start(
        tenant_id=_TENANT,
        app_id=_APP,
        source_id=_SOURCE,
        source_url="https://d.example",
    )
    await asyncio.sleep(0)

    await indexer.close()

    assert store.events == ["indexing", "fail"]
    assert not indexer._running


async def test_close_never_fails_sources_whose_crawl_never_started() -> None:
    gate = asyncio.Event()
    store = FakeStore()
    indexer = Indexer(
        crawler=FakeCrawler(gate=gate),
        embedder=FakeEmbedder(),
        store=store,
        chunk_max_chars=2_000,
        crawl_max_concurrent=1,
    )
    source_b = "019813f2-0000-7000-8000-0000000000f4"
    for source_id in (_SOURCE, source_b):
        indexer.start(
            tenant_id=_TENANT,
            app_id=_APP,
            source_id=source_id,
            source_url="https://d.example",
        )
    for _ in range(10):
        await asyncio.sleep(0)

    await indexer.close()

    assert store.events == ["indexing", "fail"]


async def test_concurrent_crawls_are_bounded_by_the_slot_limit() -> None:
    gate = asyncio.Event()
    store = FakeStore()
    indexer = Indexer(
        crawler=FakeCrawler(gate=gate),
        embedder=FakeEmbedder(),
        store=store,
        chunk_max_chars=2_000,
        crawl_max_concurrent=1,
    )
    source_b = "019813f2-0000-7000-8000-0000000000f4"
    for source_id in (_SOURCE, source_b):
        indexer.start(
            tenant_id=_TENANT,
            app_id=_APP,
            source_id=source_id,
            source_url="https://d.example",
        )
    for _ in range(10):
        await asyncio.sleep(0)
    assert store.events.count("indexing") == 1

    gate.set()
    for _ in range(200):
        if sum(event.startswith("complete") for event in store.events) == 2:
            break
        await asyncio.sleep(0)
    assert sum(event.startswith("complete") for event in store.events) == 2


async def test_empty_crawl_never_sweeps_the_old_index() -> None:
    store = FakeStore()
    service = _service(store, FakeCrawler(pages=[]))

    await service.Reindex(_request(), _context())
    await _settled(store)

    assert store.events == ["indexing", "fail"]


async def test_malformed_ids_are_rejected_before_any_crawl() -> None:
    store = FakeStore()
    service = _service(store, FakeCrawler())
    request = knowledge_pb2.ReindexRequest(
        tenant_id="not-a-uuid", app_id=_APP, source_id=_SOURCE
    )

    with pytest.raises(AbortRaised):
        await service.Reindex(request, _context())
    assert store.events == []
