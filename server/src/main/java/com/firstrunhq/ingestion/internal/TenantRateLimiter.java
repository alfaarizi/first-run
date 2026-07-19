package com.firstrunhq.ingestion.internal;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Meters each tenant's event throughput, so one noisy tenant cannot starve the rest. */
@Component
class TenantRateLimiter {

  private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
  private final IngestProperties properties;

  TenantRateLimiter(IngestProperties properties) {
    this.properties = properties;
  }

  /** Returns 0 when the batch may pass, otherwise whole seconds until enough tokens refill. */
  long retryAfterSeconds(UUID tenantId, long events) {
    Bucket bucket = buckets.computeIfAbsent(tenantId, tenant -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(events);
    if (probe.isConsumed()) {
      return 0;
    }
    return Math.max(
        1, Math.ceilDiv(probe.getNanosToWaitForRefill(), Duration.ofSeconds(1).toNanos()));
  }

  /**
   * One in-process token bucket per tenant, counted in events. Capacity bounds the burst and the
   * refill rate bounds sustained throughput. Per-instance on purpose, so a horizontal scale-out
   * multiplies the effective limit by the instance count.
   */
  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(
            limit ->
                limit
                    .capacity(properties.rateCapacity())
                    .refillGreedy(properties.rateRefillPerSecond(), Duration.ofSeconds(1)))
        .build();
  }
}
