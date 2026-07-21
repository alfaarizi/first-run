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
 * Holds each user's just-completed answers so a stream that reconnects across the gap replays the
 * {@code done} frame it missed. Unlike a nudge, an answer is not cursor-tracked: a reconnecting
 * stream drains every still-live done, and the widget applies only the one its pending question
 * awaits. A Redis failure degrades to live-only delivery instead of failing the answer.
 */
@Component
class AnswerReplayBuffer {

  // One user rarely has more than one answer in flight, so a small cap bounds their memory.
  private static final int MAX_BUFFERED_PER_USER = 4;

  // Long enough to cover a browser reload's reconnect, short enough that a done never resurfaces
  // in a later question's slot. Each frame carries its own deadline, so a newer answer landing in
  // the buffer never extends an older one's life.
  private static final Duration BUFFER_TTL = Duration.ofMinutes(2);

  private static final Logger log = LoggerFactory.getLogger(AnswerReplayBuffer.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  AnswerReplayBuffer(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** Records the completed answer for a brief replay window, degrading to live-only on failure. */
  void append(UUID appId, String endUserHash, DoneFrame frame) {
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
      log.warn("answer {} not buffered for replay", frame.messageId(), redisDown);
      return;
    }

    // The push alone matters: trimming or expiry hiccups both degrade safely, because replay
    // filters by frame age and the next append retrims.
    try {
      redis.opsForList().trim(key, 0, MAX_BUFFERED_PER_USER - 1);
      redis.expire(key, BUFFER_TTL);
    } catch (DataAccessException degraded) {
      log.warn("answer buffer maintenance failed after {}", frame.messageId(), degraded);
    }
  }

  /** Returns the user's still-live buffered answers, oldest first, for a reconnecting stream. */
  List<DoneFrame> replay(UUID appId, String endUserHash) {
    List<String> entries;
    try {
      entries = redis.opsForList().range(key(appId, endUserHash), 0, -1);
    } catch (DataAccessException redisDown) {
      log.warn("answer replay unavailable for this reconnect", redisDown);
      return List.of();
    }

    Instant cutoff = Instant.now().minus(BUFFER_TTL);
    List<DoneFrame> frames = new ArrayList<>();
    for (String entry : entries == null ? List.<String>of() : entries) {
      Buffered buffered = read(entry);
      // Entries sit newest first, so the first expired one ends the live run.
      if (buffered == null) {
        continue;
      }
      if (buffered.at().isBefore(cutoff)) {
        break;
      }
      frames.addFirst(buffered.frame());
    }
    return frames;
  }

  /** Parses one stored entry, dropping a corrupt one rather than failing the replay. */
  private @Nullable Buffered read(String entry) {
    try {
      return objectMapper.readValue(entry, Buffered.class);
    } catch (JsonProcessingException corrupt) {
      log.warn("dropping an unreadable answer replay entry", corrupt);
      return null;
    }
  }

  /** Builds the buffer key for one user's answers. */
  private static String key(UUID appId, String endUserHash) {
    return "answer-replay:%s:%s".formatted(appId, endUserHash);
  }

  /** The stored form of one answer, carrying its buffering time so replay can expire it by age. */
  record Buffered(Instant at, DoneFrame frame) {}
}
