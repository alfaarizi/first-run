"""Deterministic, hermetic Langfuse settings for the test session."""

from collections.abc import Iterator

import pytest

from agent.config import get_settings


@pytest.fixture(autouse=True)
def langfuse_test_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Give every test fixed Langfuse config and an isolated settings cache."""
    monkeypatch.setenv("LANGFUSE_HOST", "http://localhost:3000")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "lf_pk_local_test")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "lf_sk_local_test")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()
