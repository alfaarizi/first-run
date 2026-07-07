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
    String key = key(envelope);
    HashOperations<String, String, String> features = redis.opsForHash();
    Map<String, String> session = features.entries(key);
    Instant eventAt = envelope.timestamp();
    String path = pagePath(envelope);
    String storedPath = path == null ? "" : path;
    Map<String, String> updates = new LinkedHashMap<>();

    if (session.isEmpty()) {
      updates.put("started_at", eventAt.toString());
    }
    // A retry is an exact repeat of the previous event, same name and same page.
    if (envelope.event().equals(session.get("last_event"))
        && storedPath.equals(session.getOrDefault("last_event_path", ""))) {
      features.increment(key, "retries", 1);
    }
    if (ERROR.equals(envelope.event())) {
      features.increment(key, "errors", 1);
    }
    String lastPath = session.get("last_path");
    if (path != null && !path.equals(lastPath)) {
      // Returning to the page before the last distinct one is the abandonment loop.
      if (path.equals(session.get("prev_path"))) {
        features.increment(key, "backtracks", 1);
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
        updates.put("step_started_at", eventAt.toString());
      }
    }
    // Dwell runs from the step's opening, or from the session's start when no step is open.
    String dwellAnchor =
        step == null
            ? session.get("started_at")
            : stepChanged ? eventAt.toString() : session.get("step_started_at");
    Instant dwellFrom = dwellAnchor == null ? eventAt : Instant.parse(dwellAnchor);
    // Event times can arrive out of order, so dwell never reads negative and, on an unchanged
    // step, never shrinks below what a newer event already recorded.
    long dwellSeconds = Math.max(0, Duration.between(dwellFrom, eventAt).toSeconds());
    if (!stepChanged) {
      dwellSeconds =
          Math.max(dwellSeconds, Long.parseLong(session.getOrDefault("dwell_seconds", "0")));
    }
    updates.put("dwell_seconds", String.valueOf(dwellSeconds));

    updates.put("last_event", envelope.event());
    updates.put("last_event_path", storedPath);
    updates.put("last_event_at", eventAt.toString());
    features.putAll(key, updates);
    redis.expire(key, IDLE_EXPIRY);
  }

  // Batches may omit session_id, so the user hash carries the same idle-window semantics. The
  // type prefix keeps a UUID-shaped user hash from colliding with another user's session_id.
  private static String key(EventEnvelope envelope) {
    return envelope.sessionId() != null
        ? "session:%s:sid:%s".formatted(envelope.appId(), envelope.sessionId())
        : "session:%s:user:%s".formatted(envelope.appId(), envelope.endUserHash());
  }

  private static @Nullable String pagePath(EventEnvelope envelope) {
    Map<String, Object> properties = envelope.properties();
    if (!PAGE_VIEW.equals(envelope.event()) || properties == null) {
      return null;
    }
    return properties.get("path") instanceof String path ? path : null;
  }
}
