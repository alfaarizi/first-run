"""The export mask keeps end-user free text out of the trace store."""

from langfuse.types import MaskOtelSpansParams, OtelSpanData, OtelSpanIdentifier

from agent.tracing import _drop_text


def _batch(attributes: dict[str, str]) -> MaskOtelSpansParams:
    identifier = OtelSpanIdentifier(trace_id="t1", span_id="s1")
    return MaskOtelSpansParams(
        spans={
            identifier: OtelSpanData(
                trace_id="t1",
                span_id="s1",
                parent_span_id=None,
                name="converse",
                instrumentation_scope_name="langfuse",
                instrumentation_scope_version=None,
                attributes=attributes,
                resource_attributes={},
            )
        }
    )


def _deleted(attributes: dict[str, str]) -> set[str]:
    """Collect the attribute keys the mask strips from one batch."""
    result = _drop_text(params=_batch(attributes))
    return {
        key
        for patch in result.span_patches.values()
        if patch is not None
        for key in patch.delete_attributes
    }


def test_drops_every_channel_an_input_or_output_rides_in() -> None:
    # A node that traced a question, and a third-party instrumentation nobody
    # reviewed, both lose their text here rather than at the call site.
    carried = {
        "langfuse.observation.input": '{"question": "what is my api key"}',
        "langfuse.observation.output": '{"answer": "call me at 555-0100"}',
        "langfuse.trace.input": "reset my password for bob@example.com",
        "input.value": "who am i",
        "output.value": "you are bob",
        "gen_ai.prompt.0.content": "what is my api key",
        "gen_ai.completion.0.content": "your key is sk-live-1",
        "mlflow.spanInputs": "what is my api key",
    }

    assert _deleted(carried) == set(carried)


def test_keeps_the_ids_counts_and_urls_a_trace_is_read_by() -> None:
    kept = {
        "langfuse.observation.metadata.tenant_id": "019813f2",
        "langfuse.observation.metadata.question_chars": "42",
        "langfuse.observation.metadata.sources": "https://docs.example.com/setup",
        "langfuse.observation.model.name": "claude-haiku-4-5",
    }

    assert _deleted(kept) == set()


def test_leaves_a_clean_batch_unpatched() -> None:
    assert (
        _drop_text(params=_batch({"langfuse.observation.level": "ERROR"})).span_patches
        == {}
    )
