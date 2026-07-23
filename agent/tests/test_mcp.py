"""The MCP tools mirror the internal contracts: scoped reads, propose only."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

import pytest
from langfuse import Langfuse
from mcp import ClientSession
from mcp.server.fastmcp import FastMCP
from mcp.shared.memory import create_connected_server_and_client_session
from mcp.types import CallToolResult, TextContent

from agent.mcp.server import build_mcp_server
from agent.mcp.timeline import MilestoneProgress
from agent.retrieval.search import RetrievedChunk
from agent.schemas.proposal import UnregisteredActionError, propose

_TENANT = "019813f2-0000-7000-8000-0000000000f1"
_APP = "019813f2-0000-7000-8000-0000000000f2"
_TOP_K = 4

_CHUNK = RetrievedChunk(
    source_url="https://docs.example.com/setup",
    heading_path=("Setup", "Data sources"),
    content="Connect a data source from Settings.",
)

_PROGRESS = MilestoneProgress(
    milestone_name="project_created",
    state="COMPLETED",
    started_at=datetime(2026, 7, 1, tzinfo=UTC),
    completed_at=datetime(2026, 7, 2, tzinfo=UTC),
)


class RecordingEmbedder:
    """Returns a fixed query embedding, recording the text it was given."""

    def __init__(self) -> None:
        self.texts: list[str] = []

    async def embed_query(self, text: str) -> list[float]:
        self.texts.append(text)
        return [0.1, 0.2]


class RecordingSearcher:
    """Returns the fixture chunk, recording the scope it was given."""

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def search(
        self, *, tenant_id: str, app_id: str, embedding: list[float], limit: int
    ) -> list[RetrievedChunk]:
        self.calls.append(
            {
                "tenant_id": tenant_id,
                "app_id": app_id,
                "embedding": embedding,
                "limit": limit,
            }
        )
        return [_CHUNK]


class RecordingTimeline:
    """Returns the fixture progress, recording the scope it was given."""

    def __init__(self) -> None:
        self.calls: list[dict[str, str]] = []

    async def read(
        self, *, tenant_id: str, app_id: str, end_user_hash: str
    ) -> list[MilestoneProgress]:
        self.calls.append(
            {
                "tenant_id": tenant_id,
                "app_id": app_id,
                "end_user_hash": end_user_hash,
            }
        )
        return [_PROGRESS]


@dataclass
class Harness:
    """The fakes one connected server is built over."""

    embedder: RecordingEmbedder = field(default_factory=RecordingEmbedder)
    searcher: RecordingSearcher = field(default_factory=RecordingSearcher)
    timeline: RecordingTimeline = field(default_factory=RecordingTimeline)


def _build_server(
    harness: Harness, *, tenant_id: str = _TENANT, app_id: str = _APP
) -> FastMCP:
    """Build the server under test over the harness fakes."""
    return build_mcp_server(
        tenant_id=tenant_id,
        app_id=app_id,
        embedder=harness.embedder,
        searcher=harness.searcher,
        timeline=harness.timeline,
        langfuse=Langfuse(tracing_enabled=False),
        top_k=_TOP_K,
    )


@asynccontextmanager
async def _connected(harness: Harness) -> AsyncIterator[ClientSession]:
    """Yield a client session against a server built over the harness.

    A context manager rather than a fixture because the in-memory transport's
    cancel scopes must enter and exit in one task, and pytest-asyncio tears
    async fixtures down in another.
    """
    mcp = _build_server(harness)
    # The underlying protocol server is what the in-memory transport
    # connects, the pattern the SDK's own test suite uses.
    async with create_connected_server_and_client_session(mcp._mcp_server) as session:
        yield session


def _error_text(result: CallToolResult) -> str:
    assert result.isError
    content = result.content[0]
    assert isinstance(content, TextContent)
    return content.text


async def test_the_server_lists_exactly_the_three_tools() -> None:
    async with _connected(Harness()) as session:
        tools = {tool.name: tool for tool in (await session.list_tools()).tools}
    assert set(tools) == {"docs_search", "user_timeline", "propose_action"}
    assert tools["docs_search"].annotations is not None
    assert tools["docs_search"].annotations.readOnlyHint
    assert tools["user_timeline"].annotations is not None
    assert tools["user_timeline"].annotations.readOnlyHint
    assert tools["propose_action"].annotations is None


async def test_no_tool_lets_the_client_name_a_tenant() -> None:
    async with _connected(Harness()) as session:
        tools = (await session.list_tools()).tools
    for tool in tools:
        arguments = set(tool.inputSchema["properties"])
        assert "tenant_id" not in arguments
        assert "app_id" not in arguments


async def test_docs_search_returns_chunks_scoped_to_the_pinned_app() -> None:
    harness = Harness()
    async with _connected(harness) as session:
        result = await session.call_tool("docs_search", {"query": "connect a source"})
    assert not result.isError
    assert result.structuredContent == {
        "result": [
            {
                "source_url": _CHUNK.source_url,
                "heading_path": list(_CHUNK.heading_path),
                "content": _CHUNK.content,
            }
        ]
    }
    assert harness.embedder.texts == ["connect a source"]
    assert harness.searcher.calls == [
        {
            "tenant_id": _TENANT,
            "app_id": _APP,
            "embedding": [0.1, 0.2],
            "limit": _TOP_K,
        }
    ]


def test_a_non_uuid_pinned_tenant_fails_at_build_time() -> None:
    with pytest.raises(ValueError, match="tenant_id"):
        _build_server(Harness(), tenant_id="not-a-uuid")


def test_a_tenant_pinned_without_an_app_fails_at_build_time() -> None:
    with pytest.raises(ValueError, match="app_id"):
        _build_server(Harness(), app_id="")


async def test_an_oversized_query_is_rejected_before_any_embedding() -> None:
    harness = Harness()
    async with _connected(harness) as session:
        result = await session.call_tool("docs_search", {"query": "q" * 2_001})
    assert result.isError
    assert harness.embedder.texts == []


async def test_user_timeline_returns_progress_oldest_first() -> None:
    harness = Harness()
    async with _connected(harness) as session:
        result = await session.call_tool("user_timeline", {"end_user_hash": "u-hash-1"})
    assert not result.isError
    assert result.structuredContent == {
        "result": [
            {
                "milestone_name": "project_created",
                "state": "COMPLETED",
                "started_at": "2026-07-01T00:00:00Z",
                "completed_at": "2026-07-02T00:00:00Z",
            }
        ]
    }
    assert harness.timeline.calls == [
        {"tenant_id": _TENANT, "app_id": _APP, "end_user_hash": "u-hash-1"}
    ]


async def test_propose_action_returns_the_proposal_for_a_registered_name() -> None:
    async with _connected(Harness()) as session:
        result = await session.call_tool(
            "propose_action",
            {
                "action_name": "invite_teammate",
                "registered_action_names": ["invite_teammate", "create_project"],
                "arguments": {"role": "member"},
            },
        )
    assert not result.isError
    assert result.structuredContent == {
        "action_name": "invite_teammate",
        "arguments": {"role": "member"},
    }


async def test_propose_action_refuses_a_name_outside_the_registry() -> None:
    async with _connected(Harness()) as session:
        result = await session.call_tool(
            "propose_action",
            {
                "action_name": "drop_database",
                "registered_action_names": ["invite_teammate"],
            },
        )
    assert "drop_database" in _error_text(result)


async def test_an_empty_registry_proposes_nothing() -> None:
    async with _connected(Harness()) as session:
        result = await session.call_tool(
            "propose_action",
            {"action_name": "invite_teammate", "registered_action_names": []},
        )
    assert "invite_teammate" in _error_text(result)


def test_propose_builds_the_proposal_from_a_registered_name() -> None:
    proposal = propose(
        action_name="invite_teammate",
        arguments={"role": "member"},
        registered_action_names=["invite_teammate"],
    )
    assert proposal.action_name == "invite_teammate"
    assert proposal.arguments == {"role": "member"}


def test_propose_raises_for_an_unregistered_name() -> None:
    with pytest.raises(UnregisteredActionError, match="drop_database"):
        propose(
            action_name="drop_database",
            arguments={},
            registered_action_names=["invite_teammate"],
        )
