"""Runtime configuration read from the environment."""

from functools import lru_cache

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Environment-backed settings.

    The Langfuse fields have no defaults on purpose. A missing field makes
    instantiation raise ``pydantic.ValidationError``, so the service refuses
    to start unconfigured.
    """

    langfuse_host: str
    langfuse_public_key: str
    langfuse_secret_key: str


@lru_cache
def get_settings() -> Settings:
    """Return the settings, reading the environment once per process."""
    return Settings()
