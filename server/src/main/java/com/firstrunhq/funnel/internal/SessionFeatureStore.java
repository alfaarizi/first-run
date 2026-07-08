package com.firstrunhq.funnel.internal;

import com.firstrunhq.ingestion.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps one Redis hash of stuck-signal features per session: dwell on the current step, retries,
 * backtracking, and errors. The 30-minute idle expiry closes the session, mirroring the widget's
 * {@code session_id} rotation.
 */
@Component
class SessionFeatureStore {

  private static final Duration IDLE_EXPIRY = Duration.ofMinutes(30);
  private static final String PAGE_VIEW = "fr.page_view";
  private static final String ERROR = "fr.error";

  private final StringRedisTemplate redis;

  SessionFeatureStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  void record(EventEnvelope envelope, @Nullable Integer currentStep) {
    String key = sessionKey(envelope);
    HashOperations<String, String, String> features = redis.opsForHash();
    Map<String, String> session = features.entries(key);

    // A crash between this apply and the dedupe claim redelivers the event, so the stored id
    // turns the replay into a no-op instead of a double count.
    if (envelope.id().toString().equals(session.get("last_event_id"))) {
      // The crash can also cut off the expiry below, leaving the key immortal, so restore it.
      redis.expire(key, IDLE_EXPIRY);
      return;
    }

    Instant eventAt = envelope.timestamp();

    // Event times can interleave, so new steps anchor at the newest time the session recorded,
    // never at a stale one.
    Instant newestAt = latest(eventAt, session.get("newest_event_at"));

    String path = pagePath(envelope);
    String storedPath = path == null ? "" : path;
    Map<String, String> updates = new LinkedHashMap<>();

    if (session.isEmpty()) {
      updates.put("started_at", eventAt.toString());
    }
    // A retry is an exact repeat of the previous event, same name and same page.
    if (envelope.event().equals(session.get("last_event"))
        && storedPath.equals(session.getOrDefault("last_event_path", ""))) {
      increment(session, updates, "retries");
    }

    if (ERROR.equals(envelope.event())) {
      increment(session, updates, "errors");
    }

    String lastPath = session.get("last_path");
    if (path != null && !path.equals(lastPath)) {
      // Returning to the page before the last distinct one is the abandonment loop.
      if (path.equals(session.get("prev_path"))) {
        increment(session, updates, "backtracks");
      }
      if (lastPath != null) {
        updates.put("prev_path", lastPath);
      }
      updates.put("last_path", path);
    }

    String step = currentStep == null ? null : String.valueOf(currentStep);
    boolean stepChanged = !Objects.equals(step, session.get("step_position"));
    if (stepChanged) {
      if (step == null) {
        features.delete(key, "step_position", "step_started_at");
      } else {
        updates.put("step_position", step);
        updates.put("step_started_at", newestAt.toString());
      }
    }
    // Dwell runs from the step's opening, or from the session's start when no step is open.
    String dwellAnchor =
        step == null
            ? session.get("started_at")
            : stepChanged ? updates.get("step_started_at") : session.get("step_started_at");
    Instant dwellFrom = dwellAnchor == null ? eventAt : Instant.parse(dwellAnchor);
    // Event times can arrive out of order, so dwell never reads negative and, on an unchanged
    // step, never shrinks below what a newer event already recorded.
    long dwellSeconds = Math.max(0, Duration.between(dwellFrom, eventAt).toSeconds());
    if (!stepChanged) {
      dwellSeconds =
          Math.max(dwellSeconds, Long.parseLong(session.getOrDefault("dwell_seconds", "0")));
    }

    updates.put("dwell_seconds", String.valueOf(dwellSeconds));
    updates.put("newest_event_at", newestAt.toString());
    updates.put("last_event_id", envelope.id().toString());
    updates.put("last_event", envelope.event());
    updates.put("last_event_path", storedPath);
    updates.put("last_event_at", eventAt.toString());

    // One write carries the counters with the event id, so a crash redelivers all or nothing.
    features.putAll(key, updates);
    redis.expire(key, IDLE_EXPIRY);
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
