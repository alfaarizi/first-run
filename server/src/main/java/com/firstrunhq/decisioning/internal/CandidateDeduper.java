package com.firstrunhq.decisioning.internal;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Remembers each delivered candidate's flagging event UUID for 24 hours, because a redelivered
 * flagging event can emit a candidate copy under a fresh id while {@code event_id} stays fixed. The
 * group qualifier separates these claims from the other consumers'.
 */
@Component
class CandidateDeduper {

  private static final Duration CLAIM_TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;

  CandidateDeduper(StringRedisTemplate redis) {
    this.redis = redis;
  }

  boolean isClaimed(UUID appId, UUID eventId) {
    return Boolean.TRUE.equals(redis.hasKey(key(appId, eventId)));
  }

  /** Runs only after a delivered push, so a crash or an empty registry never retires the nudge. */
  void claim(UUID appId, UUID eventId) {
    redis.opsForValue().set(key(appId, eventId), "", CLAIM_TTL);
  }

  private static String key(UUID appId, UUID eventId) {
    return "dedupe:%s:%s:%s".formatted(CandidateProcessor.GROUP, appId, eventId);
  }
}
