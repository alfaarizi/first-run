"""Answer stream shapes. The model's raw output never leaves unparsed."""

from pydantic import BaseModel, Field


class AnswerToken(BaseModel):
    """One streamed span of answer text."""

    text: str


class Citation(BaseModel):
    """A pointer into the tenant's docs grounding the current answer."""

    source_url: str = Field(min_length=1)
    title: str
    snippet: str


class AnswerDone(BaseModel):
    """Marks the answer complete."""

    input_tokens: int = 0
    output_tokens: int = 0


AnswerEvent = AnswerToken | Citation | AnswerDone
