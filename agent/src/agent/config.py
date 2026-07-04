"""Runtime configuration read from the environment."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Environment-backed settings.

    The Langfuse fields have no defaults on purpose. A missing field makes
    instantiation raise ``pydantic.ValidationError``, so the service refuses
    to start unconfigured.
    """

    app_name: str = "firstrun-agent"
    langfuse_host: str
    langfuse_public_key: str
    langfuse_secret_key: str


settings = Settings()
