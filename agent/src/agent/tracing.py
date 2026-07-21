"""Opens the trace client the agent's spans export through."""

from langfuse import Langfuse
from langfuse.types import (
    MaskOtelSpansParams,
    MaskOtelSpansResult,
    OtelSpanIdentifier,
    OtelSpanPatch,
)

# Every attribute Langfuse reads as an observation's input or output, including the
# conventions a third-party instrumentation would emit. Spans carry ids, counts, and
# doc urls, so dropping these leaves an end user's question no field to travel in.
_TEXT_ATTRIBUTES = (
    "langfuse.observation.input",
    "langfuse.observation.output",
    "langfuse.trace.input",
    "langfuse.trace.output",
    "input.value",
    "output.value",
    "gen_ai.prompt",
    "gen_ai.completion",
    "mlflow.spanInputs",
    "mlflow.spanOutputs",
)


def build_tracer(*, public_key: str, secret_key: str, host: str) -> Langfuse:
    """Open the trace client, with the text mask on every batch it exports."""
    return Langfuse(
        public_key=public_key,
        secret_key=secret_key,
        host=host,
        mask_otel_spans=_drop_text,
    )


def _drop_text(*, params: MaskOtelSpansParams) -> MaskOtelSpansResult:
    """Delete every input and output attribute before the batch leaves the process.

    The nodes already trace without free text. This runs at the export seam so the
    guarantee holds for spans they do not write, and survives the node that forgets.
    """
    patches: dict[OtelSpanIdentifier, OtelSpanPatch] = {}
    for identifier, span in params.spans.items():
        carried = tuple(
            attribute for attribute in span.attributes if _carries_text(attribute)
        )
        if carried:
            patches[identifier] = OtelSpanPatch(delete_attributes=carried)
    return MaskOtelSpansResult(span_patches=patches)


def _carries_text(attribute: str) -> bool:
    """Report whether the attribute is one an input or output rides in."""
    # Prefix, not equality: the gen_ai convention indexes its keys per message.
    return attribute.startswith(_TEXT_ATTRIBUTES)
