"""Serves ConversationService over grpc.aio."""

import logging
from collections.abc import AsyncIterator

import grpc
from langfuse import Langfuse
from langgraph.graph.state import CompiledStateGraph

from agent.graph.build import ConversationState
from agent.grpc_validation import abort_unless_uuids
from agent.llm.client import Turn
from agent.schemas.answer import AnswerToken, Citation
from firstrun.v1 import conversation_pb2
from firstrun.v1.conversation_pb2_grpc import ConversationServiceServicer

log = logging.getLogger(__name__)


class ConversationService(ConversationServiceServicer):  # type: ignore[misc]
    """Streams grounded answers for one conversation per call."""

    def __init__(
        self,
        graph: CompiledStateGraph[ConversationState],
        langfuse: Langfuse,
        max_turns: int,
    ) -> None:
        """Serve the compiled graph, keeping at most ``max_turns`` of history."""
        self._graph = graph
        self._langfuse = langfuse
        self._max_turns = max_turns

    async def Converse(
        self,
        request_iterator: AsyncIterator[conversation_pb2.ConverseRequest],
        context: grpc.aio.ServicerContext,
    ) -> AsyncIterator[conversation_pb2.ConverseResponse]:
        """Answer each user message on the stream, context frame first."""
        conversation = await _read_context(request_iterator, context)
        history: list[Turn] = []

        async for frame in request_iterator:
            if frame.WhichOneof("frame") != "user_message":
                await context.abort(
                    grpc.StatusCode.INVALID_ARGUMENT,
                    "expected a user_message frame after the context",
                )
            message = frame.user_message
            answer_text: list[str] = []
            failed = False

            with self._langfuse.start_as_current_observation(
                name="converse",
                as_type="span",
                input={"question": message.text},
                metadata={
                    "conversation_id": conversation.conversation_id,
                    "message_id": message.message_id,
                    "tenant_id": conversation.tenant_id,
                },
            ) as span:
                state = _initial_state(conversation, message.text, history)
                try:
                    async for event in self._graph.astream(state, stream_mode="custom"):
                        if isinstance(event, AnswerToken):
                            answer_text.append(event.text)
                        response = _to_response(message.message_id, event)
                        if response is not None:
                            yield response
                except Exception:
                    # The stream outlives one bad answer. The failed flag tells
                    # the server that any tokens already streamed are truncated.
                    failed = True
                    log.exception("answer failed for message %s", message.message_id)
                    span.update(level="ERROR", status_message="answer failed")
                span.update(output={"answer": "".join(answer_text)})

            yield conversation_pb2.ConverseResponse(
                answer_done=conversation_pb2.AnswerDone(
                    message_id=message.message_id, failed=failed
                )
            )

            # A truncated answer stays out of history. Grounding later turns
            # in cut-short text would corrupt them too.
            if answer_text and not failed:
                history.append(Turn(role="user", text=message.text))
                history.append(Turn(role="assistant", text="".join(answer_text)))
                del history[: -self._max_turns]


async def _read_context(
    request_iterator: AsyncIterator[conversation_pb2.ConverseRequest],
    context: grpc.aio.ServicerContext,
) -> conversation_pb2.ConversationContext:
    """Require the contract's opening context frame and validate its ids."""
    try:
        first = await anext(request_iterator)
    except StopAsyncIteration:
        await context.abort(
            grpc.StatusCode.INVALID_ARGUMENT, "the stream opened without frames"
        )
    if first.WhichOneof("frame") != "context":
        await context.abort(
            grpc.StatusCode.INVALID_ARGUMENT,
            "the first frame must be a ConversationContext",
        )
    conversation = first.context
    await abort_unless_uuids(conversation, context, "tenant_id", "app_id")
    return conversation


def _initial_state(
    conversation: conversation_pb2.ConversationContext,
    question: str,
    history: list[Turn],
) -> ConversationState:
    """Build the graph input for one question over the conversation so far."""
    return {
        "tenant_id": conversation.tenant_id,
        "app_id": conversation.app_id,
        "question": question,
        "milestone_name": conversation.milestone_name,
        "history": list(history),
        "chunks": [],
    }


def _to_response(
    message_id: str, event: object
) -> conversation_pb2.ConverseResponse | None:
    """Map one graph event to its wire frame, or None for internal events."""
    if isinstance(event, AnswerToken):
        return conversation_pb2.ConverseResponse(
            answer_chunk=conversation_pb2.AnswerChunk(
                message_id=message_id, text=event.text
            )
        )
    if isinstance(event, Citation):
        return conversation_pb2.ConverseResponse(
            citation=conversation_pb2.Citation(
                message_id=message_id,
                source_url=event.source_url,
                title=event.title,
                snippet=event.snippet,
            )
        )
    return None
