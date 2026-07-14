package com.firstrunhq.funnel.internal;

import static com.firstrunhq.ingestion.AutoCapturedEvents.ERROR;
import static com.firstrunhq.ingestion.AutoCapturedEvents.PAGE_VIEW;

import com.firstrunhq.ingestion.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps one Redis hash per session with the stuck-signal features (dwell on the current step,
 * retries, backtracking, and errors). The 30-minute idle expiry closes the session, mirroring the
 * widget's {@code session_id} rotation.
 */
@Component
class SessionFeatureStore {

  // The session hash fields, read back by the stuck gate.
  static final String STARTED_AT = "started_at";
  static final String RETRIES = "retries";
  static final String ERRORS = "errors";
  static final String BACKTRACKS = "backtracks";
  static final String PREV_PATH = "prev_path";
  static final String LAST_PATH = "last_path";
  static final String STEP_POSITION = "step_position";
  static final String STEP_STARTED_AT = "step_started_at";
  static final String DWELL_SECONDS = "dwell_seconds";
  static final String NEWEST_EVENT_AT = "newest_event_at";
  static final String LAST_EVENT_ID = "last_event_id";
  static final String LAST_EVENT = "last_event";
  static final String LAST_EVENT_PATH = "last_event_path";
  static final String LAST_EVENT_AT = "last_event_at";
  static final String CANDIDATE_ID = "candidate_id";

  private static final Duration IDLE_EXPIRY = Duration.ofMinutes(30);

  private final StringRedisTemplate redis;

  SessionFeatureStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Applies the event to its session and returns the features after it, {@code null} when the event
   * is too old to be live activity.
   */
  @Nullable Map<String, String> record(
      EventEnvelope envelope, MilestoneProgressTracker.@Nullable CurrentStep currentStep) {
    Instant eventAt = envelope.timestamp();

    // An event older than the idle window is not live activity, so it opens no session and an
    // offline flush or earliest-offset replay cannot fabricate a stuck user from old records.
    if (Duration.between(eventAt, Instant.now()).compareTo(IDLE_EXPIRY) >= 0) {
      return null;
    }

    String key = sessionKey(envelope);
    HashOperations<String, String, String> features = redis.opsForHash();
    Map<String, String> session = features.entries(key);

    // A crash between this apply and the dedupe claim redelivers the event, so the stored id
    // turns the replay into a no-op instead of a double count.
    if (envelope.id().toString().equals(session.get(LAST_EVENT_ID))) {
      // The crash can also cut off the expiry below, leaving the key immortal, so restore it.
      redis.expire(key, IDLE_EXPIRY);
      return session;
    }

    // Event times can interleave, so a new step anchors at the session's newest recorded time, and
    // an event older than that (a backfill) drives no path transition.
    Instant newestAt = latest(eventAt, session.get(NEWEST_EVENT_AT));
    boolean advancesTime = !eventAt.isBefore(newestAt);

    String path = pagePath(envelope);
    String storedPath = path == null ? "" : path;
    Map<String, String> updates = new LinkedHashMap<>();

    if (session.isEmpty()) {
      updates.put(STARTED_AT, eventAt.toString());
    }

    // A retry is an exact repeat of the previous event, same name and same page.
    if (envelope.event().equals(session.get(LAST_EVENT))
        && storedPath.equals(session.getOrDefault(LAST_EVENT_PATH, ""))) {
      increment(session, updates, RETRIES);
    }

    if (ERROR.equals(envelope.event())) {
      increment(session, updates, ERRORS);
    }

    String lastPath = session.get(LAST_PATH);
    if (advancesTime && path != null && !path.equals(lastPath)) {
      // Returning to the page before the last distinct one is the abandonment loop.
      if (path.equals(session.get(PREV_PATH))) {
        increment(session, updates, BACKTRACKS);
      }
      if (lastPath != null) {
        updates.put(PREV_PATH, lastPath);
      }
      updates.put(LAST_PATH, path);
    }

    String stepPosition = currentStep == null ? null : String.valueOf(currentStep.position());
    boolean stepPositionChanged = !Objects.equals(stepPosition, session.get(STEP_POSITION));
    if (stepPositionChanged) {
      if (stepPosition == null) {
        features.delete(key, STEP_POSITION, STEP_STARTED_AT);
        session.remove(STEP_POSITION);
        session.remove(STEP_STARTED_AT);
      } else {
        updates.put(STEP_POSITION, stepPosition);
        updates.put(STEP_STARTED_AT, newestAt.toString());
      }
    }

    // Dwell runs from the step's opening, or from the session's start when no step is open.
    String dwellAnchor =
        stepPosition == null
            ? session.get(STARTED_AT)
            : stepPositionChanged ? updates.get(STEP_STARTED_AT) : session.get(STEP_STARTED_AT);
    Instant dwellFrom = dwellAnchor == null ? eventAt : Instant.parse(dwellAnchor);

    // Event times can arrive out of order, so dwell never reads negative and, on an unchanged
    // step, never shrinks below what a newer event already recorded.
    long dwellSeconds = Math.max(0, Duration.between(dwellFrom, eventAt).toSeconds());
    if (!stepPositionChanged) {
      dwellSeconds =
          Math.max(dwellSeconds, Long.parseLong(session.getOrDefault(DWELL_SECONDS, "0")));
    }

    updates.put(DWELL_SECONDS, String.valueOf(dwellSeconds));
    updates.put(NEWEST_EVENT_AT, newestAt.toString());
    updates.put(LAST_EVENT_ID, envelope.id().toString());
    updates.put(LAST_EVENT, envelope.event());
    updates.put(LAST_EVENT_PATH, storedPath);
    updates.put(LAST_EVENT_AT, eventAt.toString());

    // One write carries the counters with the event id, so a crash redelivers all or nothing.
    features.putAll(key, updates);
    redis.expire(key, IDLE_EXPIRY);

    session.putAll(updates);
    return session;
  }

  /**
   * Stores the emitted candidate on the session, so the gate flags it once and a redelivered
   * flagging event emits no copy.
   */
  void markFlagged(EventEnvelope envelope, UUID candidateId) {
    redis
        .<String, String>opsForHash()
        .put(sessionKey(envelope), CANDIDATE_ID, candidateId.toString());
  }

  /**
   * Builds the hash key for the envelope's session. Batches may omit session_id, so the user hash
   * carries the same idle-window semantics, and the type prefix keeps a UUID-shaped user hash from
   * colliding with another user's session_id.
   */
  private static String sessionKey(EventEnvelope envelope) {
    return envelope.sessionId() != null
        ? "session:%s:sid:%s".formatted(envelope.appId(), envelope.sessionId())
        : "session:%s:user:%s".formatted(envelope.appId(), envelope.endUserHash());
  }

  private static Instant latest(Instant eventAt, @Nullable String recorded) {
    if (recorded == null) {
      return eventAt;
    }
    Instant recordedAt = Instant.parse(recorded);
    return recordedAt.isAfter(eventAt) ? recordedAt : eventAt;
  }

  private static @Nullable String pagePath(EventEnvelope envelope) {
    Map<String, Object> properties = envelope.properties();
    if (!PAGE_VIEW.equals(envelope.event()) || properties == null) {
      return null;
    }
    return properties.get("path") instanceof String path ? path : null;
  }

  /**
   * Adds one to the counter read from the snapshot, which is authoritative because one consumer
   * sees a session's events in order and the value lands in the same atomic write as the event id.
   */
  private static void increment(
      Map<String, String> session, Map<String, String> updates, String field) {
    updates.put(field, String.valueOf(Long.parseLong(session.getOrDefault(field, "0")) + 1));
  }
}
