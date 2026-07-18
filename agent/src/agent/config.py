"""Runtime configuration read from the environment."""

from functools import lru_cache

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Environment-backed settings.

    The Langfuse, database, and Voyage fields have no defaults on purpose. A
    missing field makes instantiation raise ``pydantic.ValidationError``, so
    the service refuses to start unconfigured.
    """

    langfuse_host: str
    langfuse_public_key: str
    langfuse_secret_key: str

    database_url: str
    voyage_api_key: str

    embedding_model: str = "voyage-4-lite"
    # Must match the doc_chunks vector(1024) column. 
    # Changing requires a new migration and a full reindex of every tenant.
    embedding_dimension: int = 1024

    grpc_port: int = 50051

    crawl_max_pages: int = 200
    crawl_timeout_seconds: float = 10.0
    crawl_max_response_bytes: int = 2_000_000
    chunk_max_chars: int = 2_000


@lru_cache
def get_settings() -> Settings:
    """Return the settings, reading the environment once per process."""
    return Settings()
