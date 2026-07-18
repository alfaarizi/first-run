"""The only provider access point. Model names come from settings, never callers."""

from collections.abc import Sequence

import voyageai

from agent.config import get_settings

# Comfortably under every Voyage model's per-request input cap.
_MAX_BATCH = 128


class EmbeddingClient:
    """Embeds text with the Voyage model named in settings."""

    def __init__(self) -> None:
        settings = get_settings()
        self._client = voyageai.AsyncClient(api_key=settings.voyage_api_key)
        self._model = settings.embedding_model

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        """Embed document texts for indexing.

        ``input_type`` distinguishes documents from queries because Voyage
        prepends a retrieval prompt per side, and mixing the two degrades
        retrieval quality.
        """
        embeddings: list[list[float]] = []
        for start in range(0, len(texts), _MAX_BATCH):
            batch = list(texts[start : start + _MAX_BATCH])
            result = await self._client.embed(
                batch, model=self._model, input_type="document"
            )
            embeddings.extend(result.embeddings)
        return embeddings
