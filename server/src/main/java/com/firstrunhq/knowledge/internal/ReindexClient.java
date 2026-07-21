package com.firstrunhq.knowledge.internal;

import com.firstrunhq.v1.KnowledgeServiceGrpc;
import com.firstrunhq.v1.ReindexRequest;
import com.firstrunhq.v1.ReindexStatus;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/** Asks the agent to crawl one doc source. */
@Component
class ReindexClient {

  // The RPC answers before the crawl starts, so a slow reply is a fault.
  private static final Duration DEADLINE = Duration.ofSeconds(5);

  private final KnowledgeServiceGrpc.KnowledgeServiceBlockingStub stub;
  private final Retry retry;

  /** Opens the agent channel and builds the retry policy for transient unavailability. */
  ReindexClient(GrpcChannelFactory channels) {
    this.stub = KnowledgeServiceGrpc.newBlockingStub(channels.createChannel("agent"));
    // Reindex is idempotent (a repeat answers ALREADY_RUNNING), so retrying
    // transient unavailability is safe.
    this.retry =
        Retry.of(
            "agent-reindex",
            RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(
                    IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2))
                .retryOnException(
                    failure ->
                        failure instanceof StatusRuntimeException grpcFailure
                            && grpcFailure.getStatus().getCode() == Code.UNAVAILABLE)
                .build());
  }

  /** Starts a crawl, throwing {@link StatusRuntimeException} when the agent stays unreachable. */
  ReindexStatus reindex(UUID tenantId, UUID appId, UUID sourceId, String url) {
    ReindexRequest request =
        ReindexRequest.newBuilder()
            .setTenantId(tenantId.toString())
            .setAppId(appId.toString())
            .setSourceId(sourceId.toString())
            .setSourceUrl(url)
            .build();
    return Retry.decorateSupplier(retry, () -> stub.withDeadlineAfter(DEADLINE).reindex(request))
        .get()
        .getStatus();
  }
}
