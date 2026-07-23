"""MCP server sharing the graph's tools with external clients."""

import uuid
from typing import Annotated, Protocol

from langfuse import Langfuse
from mcp.server.fastmcp import FastMCP
from mcp.types import ToolAnnotations
from pydantic import Field

from agent.mcp.timeline import MilestoneProgress
from agent.retrieval.search import RetrievedChunk
from agent.schemas.proposal import ActionProposal, propose

# The caps the message and ingest contracts fix for the same fields.
_MAX_QUERY_CHARS = 2_000
_MAX_END_USER_HASH_CHARS = 128


class QueryEmbedder(Protocol):
    """The slice of the embedding client the docs_search tool needs."""

    async def embed_query(self, text: str) -> list[float]: ...


class ChunkReader(Protocol):
    """The slice of the chunk index the docs_search tool needs."""

    async def search(
        self, *, tenant_id: str, app_id: str, embedding: list[float], limit: int
    ) -> list[RetrievedChunk]: ...


class TimelineSource(Protocol):
    """The slice of the progress reader the user_timeline tool needs."""

    async def read(
        self, *, tenant_id: str, app_id: str, end_user_hash: str
    ) -> list[MilestoneProgress]: ...


_INSTRUCTIONS = """\
FirstRun's agent tools for driving the surface in demos. Reads are scoped
server-side to the one tenant and app this server is configured for, and a
client never names a tenant. propose_action never executes anything:
execution needs the end user's confirmation, and the server revalidates
the name and scope regardless.
"""


def build_mcp_server(
    *,
    tenant_id: str,
    app_id: str,
    embedder: QueryEmbedder,
    searcher: ChunkReader,
    timeline: TimelineSource,
    langfuse: Langfuse,
    top_k: int,
) -> FastMCP:
    """Expose docs_search, user_timeline, and propose_action over the given clients.

    Every read is pinned to the given tenant and app. An MCP client is
    outside the trust boundary, so tenant identity comes from this
    configuration, never from a tool argument.
    """
    _require_uuid("tenant_id", tenant_id)
    _require_uuid("app_id", app_id)

    # Stateless: each tool call carries its full input, so a demo client
    # needs no session resumption and any replica can serve any call.
    mcp = FastMCP("firstrun-agent", instructions=_INSTRUCTIONS, stateless_http=True)
    read_only = ToolAnnotations(readOnlyHint=True)

    @mcp.tool(annotations=read_only)
    async def docs_search(
        query: Annotated[str, Field(min_length=1, max_length=_MAX_QUERY_CHARS)],
    ) -> list[RetrievedChunk]:
        """Search the app's indexed docs for the chunks nearest the query."""
        with langfuse.start_as_current_observation(
            name="docs_search", as_type="retriever"
        ) as span:
            embedding = await embedder.embed_query(query)
            chunks = await searcher.search(
                tenant_id=tenant_id,
                app_id=app_id,
                embedding=embedding,
                limit=top_k,
            )
            # Sources only. The query is an external client's free text,
            # and free text stays out of the trace.
            span.update(
                metadata={"sources": [chunk.source_url for chunk in chunks]},
            )
        return chunks

    @mcp.tool(annotations=read_only)
    async def user_timeline(
        end_user_hash: Annotated[
            str, Field(min_length=1, max_length=_MAX_END_USER_HASH_CHARS)
        ],
    ) -> list[MilestoneProgress]:
        """Return the end user's milestone progress, oldest first."""
        return await timeline.read(
            tenant_id=tenant_id,
            app_id=app_id,
            end_user_hash=end_user_hash,
        )

    @mcp.tool()
    async def propose_action(
        action_name: str,
        registered_action_names: list[str],
        arguments: dict[str, str] | None = None,
    ) -> ActionProposal:
        """Propose one registered action for the end user to confirm.

        Proposing executes nothing. The name must be in
        registered_action_names, the app's registry as the trusted caller
        supplies it, and an empty registry proposes nothing.
        """
        return propose(
            action_name=action_name,
            arguments=arguments or {},
            registered_action_names=registered_action_names,
        )

    return mcp


def _require_uuid(name: str, value: str) -> None:
    """Refuse a scope id that does not parse as a UUID."""
    try:
        uuid.UUID(value)
    except ValueError:
        raise ValueError(f"mcp {name} is not a UUID: {value!r}") from None
