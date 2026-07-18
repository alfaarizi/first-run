package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Holds each user's recently pushed nudge frames so a reconnecting stream can replay what it
 * missed. A Redis failure degrades to live-only delivery instead of failing the caller.
 */
@Component
class NudgeReplayBuffer {

  // One session rarely yields more than a few candidates, so a small cap bounds one user's memory.
  private static final int MAX_BUFFERED_PER_USER = 8;

  // The widget rotates its session after 30 idle minutes, so a nudge this old belongs to a
  // session the user has left and must not resurface in the next one. Each frame carries its own
  // deadline, because sharing the key's expiry would extend old frames whenever a new one lands.
  private static final Duration BUFFER_TTL = Duration.ofMinutes(30);

  private static final Logger log = LoggerFactory.getLogger(NudgeReplayBuffer.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  NudgeReplayBuffer(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** Records the frame for later replay and reports whether the buffer holds it. */
  boolean append(UUID appId, String endUserHash, NudgeFrame frame) {
    String key = key(appId, endUserHash);
    String entry;
    try {
      entry = objectMapper.writeValueAsString(new Buffered(Instant.now(), frame));
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }

    try {
      redis.opsForList().leftPush(key, entry);
    } catch (DataAccessException redisDown) {
      log.warn("nudge {} not buffered for replay", frame.id(), redisDown);
      return false;
    }

    // The push alone decides the outcome: reporting a trim or expiry hiccup as failure would
    // let a candidate copy buffer a duplicate nudge for the same event. Both degrade safely,
    // because replay filters by frame age and the next append retrims.
    try {
      redis.opsForList().trim(key, 0, MAX_BUFFERED_PER_USER - 1);
      redis.expire(key, BUFFER_TTL);
    } catch (DataAccessException degraded) {
      log.warn("replay buffer maintenance failed after nudge {}", frame.id(), degraded);
    }
    return true;
  }

  /**
   * Returns the still-live buffered frames after the given id, oldest first. An unknown id replays
   * the whole buffer, which is safe because trimming and expiry only drop the oldest entries, so
   * every survivor is newer than the lost cursor.
   */
  List<NudgeFrame> after(UUID appId, String endUserHash, String lastEventId) {
    List<String> entries;
    try {
      entries = redis.opsForList().range(key(appId, endUserHash), 0, -1);
    } catch (DataAccessException redisDown) {
      log.warn("replay unavailable for this reconnect", redisDown);
      return List.of();
    }

    Instant cutoff = Instant.now().minus(BUFFER_TTL);
    List<NudgeFrame> frames = new ArrayList<>();
    for (String entry : entries == null ? List.<String>of() : entries) {
      Buffered buffered = read(entry);
      if (buffered == null) {
        continue;
      }
      // entries sit newest first, so everything from the first expired frame down is older still
      if (buffered.at().isBefore(cutoff) || buffered.frame().id().toString().equals(lastEventId)) {
        break;
      }
      frames.addFirst(buffered.frame());
    }
    return frames;
  }

  private @Nullable Buffered read(String entry) {
    try {
      return objectMapper.readValue(entry, Buffered.class);
    } catch (JsonProcessingException corrupt) {
      log.warn("dropping an unreadable replay entry", corrupt);
      return null;
    }
  }

  private static String key(UUID appId, String endUserHash) {
    return "replay:%s:%s:%s".formatted(CandidateProcessor.GROUP, appId, endUserHash);
  }

  /** The stored form of one frame, carrying its buffering time so replay can expire it by age. */
  record Buffered(Instant at, NudgeFrame frame) {}
}
