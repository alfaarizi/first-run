"""Serves KnowledgeService over grpc.aio."""

import grpc

from agent.grpc_validation import abort_unless_uuids
from agent.indexing.indexer import Indexer
from firstrun.v1 import knowledge_pb2
from firstrun.v1.knowledge_pb2_grpc import KnowledgeServiceServicer


class KnowledgeService(KnowledgeServiceServicer):  # type: ignore[misc]
    """Accepts reindex requests and hands them to the indexer."""

    def __init__(self, indexer: Indexer) -> None:
        """Serve reindex requests against the given indexer."""
        self._indexer = indexer

    async def Reindex(
        self,
        request: knowledge_pb2.ReindexRequest,
        context: grpc.aio.ServicerContext,
    ) -> knowledge_pb2.ReindexResponse:
        """Start a crawl of one doc source and return immediately."""
        await abort_unless_uuids(request, context, "tenant_id", "app_id", "source_id")
        started = self._indexer.start(
            tenant_id=request.tenant_id,
            app_id=request.app_id,
            source_id=request.source_id,
            source_url=request.source_url,
        )
        status = (
            knowledge_pb2.REINDEX_STATUS_ACCEPTED
            if started
            else knowledge_pb2.REINDEX_STATUS_ALREADY_RUNNING
        )
        return knowledge_pb2.ReindexResponse(source_id=request.source_id, status=status)
