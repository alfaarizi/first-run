"""Deterministic, hermetic settings for the test session."""

from collections.abc import Iterator

import pytest

from agent.config import get_settings


@pytest.fixture(autouse=True)
def settings_test_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Give every test fixed required config and an isolated settings cache."""
    monkeypatch.setenv("LANGFUSE_HOST", "http://localhost:3000")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "lf_pk_local_test")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "lf_sk_local_test")
    monkeypatch.setenv("DATABASE_URL", "postgresql://firstrun:test@localhost/firstrun")
    monkeypatch.setenv("VOYAGE_API_KEY", "vo_local_test")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant_local_test")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()
