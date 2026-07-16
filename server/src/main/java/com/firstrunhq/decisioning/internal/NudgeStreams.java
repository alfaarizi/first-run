package com.firstrunhq.decisioning.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds the open widget streams keyed by app and end user, so a nudge finds every tab the user has
 * open. A stream that completes, times out, or errors removes itself.
 */
@Component
class NudgeStreams {

  // Under the 30-minute session idle window, so an abandoned tab's stream closes with its session.
  private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(25);

  private final Map<String, List<SseEmitter>> streams = new ConcurrentHashMap<>();

  SseEmitter register(UUID appId, String endUserHash) {
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
    String key = key(appId, endUserHash);

    // compute serializes with removal per key, so a concurrent removal never strands a live stream
    streams.compute(
        key,
        (unused, emitters) -> {
          List<SseEmitter> live = emitters == null ? new CopyOnWriteArrayList<>() : emitters;
          live.add(emitter);
          return live;
        });

    Runnable remove = () -> remove(key, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(error -> remove.run());

    // A first frame commits the response headers, so EventSource opens now, not on the first nudge.
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException | IllegalStateException gone) {
      emitter.completeWithError(gone);
    }
    return emitter;
  }

  /**
   * Sends the nudge on every stream the user has open. The frame id feeds {@code Last-Event-ID}.
   */
  void pushNudge(UUID appId, String endUserHash, UUID nudgeId, String text) {
    for (SseEmitter emitter : streams.getOrDefault(key(appId, endUserHash), List.of())) {
      try {
        emitter.send(
            SseEmitter.event()
                .name("nudge")
                .id(nudgeId.toString())
                .data(new NudgeFrame(nudgeId, text), MediaType.APPLICATION_JSON));
      } catch (IOException | IllegalStateException gone) {
        emitter.completeWithError(gone);
      }
    }
  }

  private void remove(String key, SseEmitter emitter) {
    streams.computeIfPresent(
        key,
        (unused, emitters) -> {
          emitters.remove(emitter);
          return emitters.isEmpty() ? null : emitters;
        });
  }

  private static String key(UUID appId, String endUserHash) {
    return appId + ":" + endUserHash;
  }
}
