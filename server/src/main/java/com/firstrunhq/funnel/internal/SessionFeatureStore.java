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

  /** Keeps the features in Redis so any instance can pick up a session mid-stream. */
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

    // An event older than the idle window is not live activity, so it opens no session.
    // An offline flush or an earliest-offset replay must not fabricate a stuck user.
    if (Duration.between(eventAt, Instant.now()).compareTo(IDLE_EXPIRY) >= 0) {
      return null;
    }

    String key = sessionKey(envelope);
    HashOperations<String, String, String> features = redis.opsForHash();
    Map<String, String> session = features.entries(key);

    // A crash between this apply and the dedupe claim redelivers the event.
    // The stored id turns the replay into a no-op instead of a double count.
    if (envelope.id().toString().equals(session.get(LAST_EVENT_ID))) {
      // The crash can also cut off the expiry below, leaving the key immortal, so restore it.
      redis.expire(key, IDLE_EXPIRY);
      return session;
    }

    // Event times can interleave, so a new step anchors at the session's newest
    // recorded time, and an event older than that drives no path transition.
    Instant newestAt = latest(eventAt, session.get(NEWEST_EVENT_AT));
    boolean advancesTime = !eventAt.isBefore(newestAt);

    String path = pagePath(envelope);
    String storedPath = path == null ? "" : path;
    Map<String, String> updates = new LinkedHashMap<>();

    if (session.isEmpty()) {
      updates.put(STARTED_AT, eventAt.toString());
    }

    countRetry(envelope, storedPath, session, updates);
    if (ERROR.equals(envelope.event())) {
      increment(session, updates, ERRORS);
    }
    if (advancesTime) {
      trackPathTransition(path, session, updates);
    }
    boolean stepPositionChanged = trackStep(key, currentStep, newestAt, session, updates);
    updates.put(
        DWELL_SECONDS,
        String.valueOf(dwellSeconds(eventAt, stepPositionChanged, session, updates)));

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

  /** A retry is an exact repeat of the previous event, same name and same page. */
  private static void countRetry(
      EventEnvelope envelope,
      String storedPath,
      Map<String, String> session,
      Map<String, String> updates) {
    if (envelope.event().equals(session.get(LAST_EVENT))
        && storedPath.equals(session.getOrDefault(LAST_EVENT_PATH, ""))) {
      increment(session, updates, RETRIES);
    }
  }

  /** A page change moves the path pair along, counting the abandonment loop as a backtrack. */
  private static void trackPathTransition(
      @Nullable String path, Map<String, String> session, Map<String, String> updates) {
    String lastPath = session.get(LAST_PATH);
    if (path == null || path.equals(lastPath)) {
      return;
    }
    // Returning to the page before the last distinct one is the abandonment loop.
    if (path.equals(session.get(PREV_PATH))) {
      increment(session, updates, BACKTRACKS);
    }
    if (lastPath != null) {
      updates.put(PREV_PATH, lastPath);
    }
    updates.put(LAST_PATH, path);
  }

  /**
   * Records a step change, anchoring a new step at the newest recorded time. Leaving the funnel
   * deletes the step fields at once, because the batched write can only add.
   */
  private boolean trackStep(
      String key,
      MilestoneProgressTracker.@Nullable CurrentStep currentStep,
      Instant newestAt,
      Map<String, String> session,
      Map<String, String> updates) {
    String stepPosition = currentStep == null ? null : String.valueOf(currentStep.position());
    if (Objects.equals(stepPosition, session.get(STEP_POSITION))) {
      return false;
    }
    if (stepPosition == null) {
      redis.<String, String>opsForHash().delete(key, STEP_POSITION, STEP_STARTED_AT);
      session.remove(STEP_POSITION);
      session.remove(STEP_STARTED_AT);
    } else {
      updates.put(STEP_POSITION, stepPosition);
      updates.put(STEP_STARTED_AT, newestAt.toString());
    }
    return true;
  }

  /**
   * Dwell runs from the step's opening, or from the session's start when no step is open. Event
   * times can arrive out of order, so dwell never reads negative and, on an unchanged step, never
   * shrinks below what a newer event already recorded.
   */
  private static long dwellSeconds(
      Instant eventAt,
      boolean stepPositionChanged,
      Map<String, String> session,
      Map<String, String> updates) {
    String stepStartedAt = updates.getOrDefault(STEP_STARTED_AT, session.get(STEP_STARTED_AT));
    boolean stepOpen = updates.getOrDefault(STEP_POSITION, session.get(STEP_POSITION)) != null;
    String dwellAnchor = stepOpen ? stepStartedAt : session.get(STARTED_AT);
    Instant dwellFrom = dwellAnchor == null ? eventAt : Instant.parse(dwellAnchor);

    long dwellSeconds = Math.max(0, Duration.between(dwellFrom, eventAt).toSeconds());
    if (!stepPositionChanged) {
      dwellSeconds =
          Math.max(dwellSeconds, Long.parseLong(session.getOrDefault(DWELL_SECONDS, "0")));
    }
    return dwellSeconds;
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

  /** Returns the later of the event's time and the session's recorded newest. */
  private static Instant latest(Instant eventAt, @Nullable String recorded) {
    if (recorded == null) {
      return eventAt;
    }
    Instant recordedAt = Instant.parse(recorded);
    return recordedAt.isAfter(eventAt) ? recordedAt : eventAt;
  }

  /** Reads the page path off a page-view event, null for every other event. */
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
