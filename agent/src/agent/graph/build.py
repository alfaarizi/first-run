"""Wires the retrieve and answer nodes into the conversation graph."""

from typing import TypedDict

from langfuse import Langfuse
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from agent.llm.client import ChatClient, EmbeddingClient, Turn
from agent.retrieval.search import ChunkSearcher, RetrievedChunk
from agent.schemas.answer import AnswerDone


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
    embedder: EmbeddingClient,
    searcher: ChunkSearcher,
    chat: ChatClient,
    langfuse: Langfuse,
    answer_model: str,
    top_k: int,
) -> CompiledStateGraph[ConversationState]:
    """Compile the retrieve-then-answer graph over the given clients."""

    async def retrieve(state: ConversationState) -> dict[str, object]:
        with langfuse.start_as_current_observation(
            name="retrieve", as_type="retriever", input={"question": state["question"]}
        ) as span:
            embedding = await embedder.embed_query(state["question"])
            chunks = await searcher.search(
                tenant_id=state["tenant_id"],
                app_id=state["app_id"],
                embedding=embedding,
                limit=top_k,
            )
            span.update(output={"chunks": len(chunks)})
        return {"chunks": chunks}

    async def answer(state: ConversationState) -> dict[str, object]:
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
