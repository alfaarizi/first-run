package com.firstrunhq.ingestion.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Claims event UUIDs at the gateway so a client retry is accepted once and produced once. */
@Component
class EventDeduper {

  private static final Duration TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;

  EventDeduper(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * The first delivery claims the key with SET NX and a 24-hour TTL (api/openapi/ingest.yaml), and
   * every redelivery inside the window fails the claim. Keys are app-scoped so a client can never
   * suppress another app's events by guessing UUIDs.
   */
  boolean firstDelivery(UUID appId, UUID eventId) {
    return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(appId, eventId), "", TTL));
  }

  /** Frees claims whose events never reached the stream, so the client's retry passes dedupe. */
  void release(UUID appId, List<UUID> eventIds) {
    redis.delete(eventIds.stream().map(eventId -> key(appId, eventId)).toList());
  }

  /** Builds the app-scoped claim key for one event. */
  private static String key(UUID appId, UUID eventId) {
    return "dedupe:%s:%s".formatted(appId, eventId);
  }
}
