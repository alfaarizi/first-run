"""Wires the retrieve and answer nodes into the conversation graph."""

from collections.abc import AsyncIterator, Sequence
from typing import Protocol, TypedDict

from langfuse import Langfuse
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from agent.llm.client import Turn
from agent.retrieval.search import RetrievedChunk
from agent.schemas.answer import AnswerDone, AnswerEvent


class QueryEmbedder(Protocol):
    """The slice of the embedding client the retrieve node needs."""

    async def embed_query(self, text: str) -> list[float]: ...


class ChunkReader(Protocol):
    """The slice of the chunk index the retrieve node needs."""

    async def search(
        self, *, tenant_id: str, app_id: str, embedding: list[float], limit: int
    ) -> list[RetrievedChunk]: ...


class AnswerSource(Protocol):
    """The slice of the chat client the answer node needs."""

    def stream_answer(
        self,
        *,
        question: str,
        chunks: Sequence[RetrievedChunk],
        history: Sequence[Turn],
        milestone_name: str,
    ) -> AsyncIterator[AnswerEvent]: ...


class ConversationState(TypedDict):
    """State one user message carries through retrieve and answer."""

    tenant_id: str
    app_id: str
    question: str
    milestone_name: str
    history: list[Turn]
    chunks: list[RetrievedChunk]


def build_graph(
    *,
    embedder: QueryEmbedder,
    searcher: ChunkReader,
    chat: AnswerSource,
    langfuse: Langfuse,
    answer_model: str,
    top_k: int,
) -> CompiledStateGraph[ConversationState]:
    """Compile the retrieve-then-answer graph over the given clients."""

    async def retrieve(state: ConversationState) -> dict[str, object]:
        """Embed the question and pull the nearest chunks into state."""
        with langfuse.start_as_current_observation(
            name="retrieve", as_type="retriever"
        ) as span:
            embedding = await embedder.embed_query(state["question"])
            chunks = await searcher.search(
                tenant_id=state["tenant_id"],
                app_id=state["app_id"],
                embedding=embedding,
                limit=top_k,
            )
            # The docs a grounding incident is read against. The question that
            # found them is the end user's free text and stays out of the trace.
            span.update(
                metadata={"sources": [chunk.source_url for chunk in chunks]},
            )
        return {"chunks": chunks}

    async def answer(state: ConversationState) -> dict[str, object]:
        """Stream the grounded answer's events out through the graph writer."""
        writer = get_stream_writer()
        with langfuse.start_as_current_observation(
            name="answer", as_type="generation", model=answer_model
        ) as generation:
            async for event in chat.stream_answer(
                question=state["question"],
                chunks=state["chunks"],
                history=state["history"],
                milestone_name=state["milestone_name"],
            ):
                if isinstance(event, AnswerDone):
                    generation.update(
                        usage_details={
                            "prompt_tokens": event.input_tokens,
                            "completion_tokens": event.output_tokens,
                        }
                    )
                writer(event)
        return {}

    builder: StateGraph[ConversationState] = StateGraph(ConversationState)
    builder.add_node("retrieve", retrieve)
    builder.add_node("answer", answer)
    builder.add_edge(START, "retrieve")
    builder.add_edge("retrieve", "answer")
    builder.add_edge("answer", END)
    return builder.compile()
