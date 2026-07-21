package com.firstrunhq.decisioning.internal;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Meters each app's chat messages, so a flooded app cannot spend unbounded model budget. */
@Component
class MessageRateLimiter {

  // Every message costs a retrieval and a model call, and one caller can send forever inside a
  // single conversation, so counting conversations bounds no spend.
  private static final long CAPACITY = 60;
  private static final long REFILL_PER_MINUTE = 60;

  private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

  /** Returns 0 when the message may pass, otherwise whole seconds until a token refills. */
  long retryAfterSeconds(UUID appId) {
    Bucket bucket = buckets.computeIfAbsent(appId, app -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return 0;
    }
    return Math.max(
        1, Math.ceilDiv(probe.getNanosToWaitForRefill(), Duration.ofSeconds(1).toNanos()));
  }

  /**
   * One token bucket per app, held per instance, so a scale-out multiplies the limit. Keyed by app
   * rather than end user because the end-user hash is client-chosen: a per-user bucket multiplies
   * by every identity the caller invents.
   */
  private static Bucket newBucket() {
    return Bucket.builder()
        .addLimit(
            limit ->
                limit.capacity(CAPACITY).refillGreedy(REFILL_PER_MINUTE, Duration.ofMinutes(1)))
        .build();
  }
}
