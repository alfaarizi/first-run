package com.firstrunhq.funnel.internal;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Remembers each applied event UUID for 24 hours so a redelivered record is skipped whole. The
 * group qualifier separates these claims from the gateway's, held before every produce.
 */
@Component
class StreamDeduper {

  private static final Duration CLAIM_TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;

  StreamDeduper(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Reports whether the event was already applied inside the claim window. */
  boolean isClaimed(UUID appId, UUID eventId) {
    return Boolean.TRUE.equals(redis.hasKey(key(appId, eventId)));
  }

  /** Runs only after the apply is durable, so a failure or crash replays instead of dropping. */
  void claim(UUID appId, UUID eventId) {
    redis.opsForValue().set(key(appId, eventId), "", CLAIM_TTL);
  }

  /** Builds the claim key, qualified by the consumer group so claims never collide. */
  private static String key(UUID appId, UUID eventId) {
    return "dedupe:%s:%s:%s".formatted(EventStreamProcessor.GROUP, appId, eventId);
  }
}
