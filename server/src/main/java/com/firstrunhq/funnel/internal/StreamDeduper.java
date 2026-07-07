package com.firstrunhq.funnel.internal;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Claims each event UUID for 24 hours so a redelivered record updates session features once. The
 * group qualifier keeps the gateway's claims, held before every produce, from reading as duplicates.
 */
@Component
class StreamDeduper {

  private static final Duration CLAIM_TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;

  StreamDeduper(StringRedisTemplate redis) {
    this.redis = redis;
  }

  boolean firstDelivery(UUID appId, UUID eventId) {
    return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(appId, eventId), "", CLAIM_TTL));
  }

  /** Frees the claim after a failed apply, so the retry or a dead-letter replay passes dedupe. */
  void release(UUID appId, UUID eventId) {
    redis.delete(key(appId, eventId));
  }

  private static String key(UUID appId, UUID eventId) {
    return "dedupe:%s:%s:%s".formatted(EventStreamProcessor.GROUP, appId, eventId);
  }
}
