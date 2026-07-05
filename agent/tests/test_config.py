"""The service must refuse to start when Langfuse configuration is missing."""

import pytest
from pydantic import ValidationError

from agent.config import Settings


def test_missing_langfuse_settings_fail_fast(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in ("LANGFUSE_HOST", "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY"):
        monkeypatch.delenv(name, raising=False)
    with pytest.raises(ValidationError):
        Settings()
