"""Converse streams parsed answer frames and keeps chunks out of the prompt."""

import json
from collections.abc import AsyncIterator, Sequence
from pathlib import Path
from typing import cast

import grpc
import pytest
from langfuse import Langfuse

from agent.graph.build import build_graph
from agent.graph.service import ConversationService
from agent.llm.client import (
    _ANSWER_SYSTEM_PROMPT,
    Turn,
    _parse_citation,
    _build_user_content,
)
from agent.retrieval.search import RetrievedChunk
from agent.schemas.answer import AnswerDone, AnswerEvent, AnswerToken, Citation
from firstrun.v1 import conversation_pb2

_TENANT = "019813f2-0000-7000-8000-0000000000f1"
_APP = "019813f2-0000-7000-8000-0000000000f2"

_CHUNK = RetrievedChunk(
    source_url="https://docs.example.com/setup",
    heading_path=("Setup", "Data sources"),
    content="Connect a data source from Settings.",
)

_FIXTURE = json.loads(
    (Path(__file__).parent / "fixtures" / "answer_events.json").read_text()
)


def _fixture_events() -> list[AnswerEvent]:
    events: list[AnswerEvent] = []
    for raw in _FIXTURE:
        if raw["type"] == "token":
            events.append(AnswerToken(text=raw["text"]))
        elif raw["type"] == "citation":
            events.append(
                Citation(
                    source_url=raw["source_url"],
                    title=raw["title"],
                    snippet=raw["snippet"],
                )
            )
        else:
            events.append(
                AnswerDone(
                    input_tokens=raw["input_tokens"],
                    output_tokens=raw["output_tokens"],
                )
            )
    return events


class FakeEmbedder:
    async def embed_query(self, text: str) -> list[float]:
        return [0.0] * 4


class FakeSearcher:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, int]] = []

    async def search(
        self, *, tenant_id: str, app_id: str, embedding: list[float], limit: int
    ) -> list[RetrievedChunk]:
        self.calls.append((tenant_id, app_id, limit))
        return [_CHUNK]


class FakeChat:
    def __init__(self, broken: bool = False, breaks_after_tokens: int = 0) -> None:
        self.broken = broken
        self.breaks_after_tokens = breaks_after_tokens
        self.histories: list[list[Turn]] = []
        self.milestones: list[str] = []

    async def stream_answer(
        self,
        *,
        question: str,
        chunks: Sequence[RetrievedChunk],
        history: Sequence[Turn] = (),
        milestone_name: str = "",
    ) -> AsyncIterator[AnswerEvent]:
        self.histories.append(list(history))
        self.milestones.append(milestone_name)
        if self.broken:
            raise RuntimeError("provider outage")
        streamed = 0
        for event in _fixture_events():
            yield event
            if isinstance(event, AnswerToken):
                streamed += 1
                if streamed == self.breaks_after_tokens:
                    raise RuntimeError("provider outage mid-stream")


class AbortRaised(Exception):
    pass


class FakeContext:
    async def abort(self, code: grpc.StatusCode, details: str) -> None:
        raise AbortRaised(f"{code}: {details}")


def _service(chat: FakeChat) -> ConversationService:
    graph = build_graph(
        embedder=FakeEmbedder(),
        searcher=FakeSearcher(),
        chat=chat,
        langfuse=Langfuse(tracing_enabled=False),
        answer_model="test-model",
        top_k=4,
    )
    return ConversationService(graph, Langfuse(tracing_enabled=False), max_turns=4)


def _context_frame() -> conversation_pb2.ConverseRequest:
    return conversation_pb2.ConverseRequest(
        context=conversation_pb2.ConversationContext(
            conversation_id="019813f2-0000-7000-8000-0000000000c1",
            tenant_id=_TENANT,
            app_id=_APP,
            end_user_hash="9f86d081884c7d65",
            session_id="019813f2-0000-7000-8000-0000000000s1",
            milestone_name="data_source_connected",
        )
    )


def _message_frame(message_id: str, text: str) -> conversation_pb2.ConverseRequest:
    return conversation_pb2.ConverseRequest(
        user_message=conversation_pb2.UserMessage(message_id=message_id, text=text)
    )


async def _frames(
    *requests: conversation_pb2.ConverseRequest,
) -> AsyncIterator[conversation_pb2.ConverseRequest]:
    for request in requests:
        yield request


def _grpc_context() -> grpc.aio.ServicerContext:
    return cast(grpc.aio.ServicerContext, FakeContext())


async def test_answers_stream_as_tokens_citation_then_done() -> None:
    service = _service(FakeChat())
    responses = [
        response
        async for response in service.Converse(
            _frames(_context_frame(), _message_frame("m1", "How do I connect?")),
            _grpc_context(),
        )
    ]

    kinds = [response.WhichOneof("frame") for response in responses]
    assert kinds == ["answer_chunk", "answer_chunk", "citation", "answer_done"]
    assert all(
        getattr(response, kind).message_id == "m1"
        for response, kind in zip(responses, kinds, strict=True)
    )
    assert responses[2].citation.source_url == "https://docs.example.com/setup"


async def test_history_carries_prior_turns_and_stays_bounded() -> None:
    chat = FakeChat()
    service = _service(chat)
    frames = _frames(
        _context_frame(),
        _message_frame("m1", "How do I connect?"),
        _message_frame("m2", "And then?"),
        _message_frame("m3", "One more."),
    )
    async for _ in service.Converse(frames, _grpc_context()):
        pass

    assert chat.histories[0] == []
    assert chat.histories[1][0] == Turn(role="user", text="How do I connect?")
    assert chat.histories[1][1].role == "assistant"
    # max_turns is 4, so the third message sees only the newest four turns.
    assert len(chat.histories[2]) == 4
    assert chat.milestones == ["data_source_connected"] * 3


async def test_a_failed_answer_yields_a_bare_done_and_keeps_the_stream() -> None:
    chat = FakeChat(broken=True)
    service = _service(chat)
    responses = [
        response
        async for response in service.Converse(
            _frames(
                _context_frame(),
                _message_frame("m1", "How do I connect?"),
                _message_frame("m2", "Still there?"),
            ),
            _grpc_context(),
        )
    ]

    kinds = [response.WhichOneof("frame") for response in responses]
    assert kinds == ["answer_done", "answer_done"]
    assert all(response.answer_done.failed for response in responses)
    # A failed answer never becomes fabricated history.
    assert chat.histories[1] == []


async def test_a_mid_stream_failure_marks_the_done_failed() -> None:
    chat = FakeChat(breaks_after_tokens=1)
    service = _service(chat)
    responses = [
        response
        async for response in service.Converse(
            _frames(
                _context_frame(),
                _message_frame("m1", "How do I connect?"),
                _message_frame("m2", "Still there?"),
            ),
            _grpc_context(),
        )
    ]

    # Tokens streamed before the failure, so the done must carry the failed
    # flag and the truncated turn must stay out of history.
    dones = [r.answer_done for r in responses if r.WhichOneof("frame") == "answer_done"]
    assert [done.failed for done in dones] == [True, True]
    assert chat.histories[1] == []


async def test_the_first_frame_must_be_the_context() -> None:
    service = _service(FakeChat())
    with pytest.raises(AbortRaised, match="ConversationContext"):
        async for _ in service.Converse(
            _frames(_message_frame("m1", "hello")), _grpc_context()
        ):
            pass


async def test_the_context_ids_must_be_uuids() -> None:
    service = _service(FakeChat())
    bad = conversation_pb2.ConverseRequest(
        context=conversation_pb2.ConversationContext(
            tenant_id="not-a-uuid", app_id=_APP
        )
    )
    with pytest.raises(AbortRaised, match="tenant_id"):
        async for _ in service.Converse(_frames(bad), _grpc_context()):
            pass


def test_chunks_enter_the_user_turn_as_search_results_never_the_system_prompt() -> None:
    content = cast(
        "list[dict[str, object]]",
        _build_user_content("How do I connect?", [_CHUNK], "data_source_connected"),
    )

    assert _CHUNK.content not in _ANSWER_SYSTEM_PROMPT
    assert content[0]["type"] == "search_result"
    assert content[0]["source"] == _CHUNK.source_url
    assert content[0]["title"] == "Setup > Data sources"
    assert content[0]["citations"] == {"enabled": True}
    assert content[-1]["type"] == "text"
    text = cast(str, content[-1]["text"])
    assert text.endswith("How do I connect?")
    assert "data_source_connected" in text


def test_a_streamed_citation_parses_and_bounds_its_snippet() -> None:
    class Raw:
        source = "https://docs.example.com/setup"
        title = None
        cited_text = "x" * 1000

    citation = _parse_citation(Raw())
    assert citation is not None
    assert citation.title == ""
    assert len(citation.snippet) == 300
