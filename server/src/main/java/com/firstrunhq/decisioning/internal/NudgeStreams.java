package com.firstrunhq.decisioning.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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

  // The signing key ships in the page, so anyone can mint a valid stream URL. The user cap retires
  // the oldest tab, and the app budget keeps one flooded app from starving the other tenants.
  private static final int MAX_STREAMS_PER_USER = 8;
  private static final int MAX_STREAMS_PER_APP = 1000;

  private final Map<String, List<SseEmitter>> streams = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> appStreamCounts = new ConcurrentHashMap<>();

  /** Opens a stream for the user, or returns null when the app's budget is spent. */
  @Nullable SseEmitter register(UUID appId, String endUserHash) {
    AtomicInteger appCount = appStreamCounts.computeIfAbsent(appId, id -> new AtomicInteger());
    if (appCount.incrementAndGet() > MAX_STREAMS_PER_APP) {
      appCount.decrementAndGet();
      return null;
    }

    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
    String key = key(appId, endUserHash);

    // compute serializes with removal per key, so a concurrent removal never strands a live stream
    List<SseEmitter> evicted = new ArrayList<>();
    streams.compute(
        key,
        (unused, emitters) -> {
          List<SseEmitter> live = emitters == null ? new CopyOnWriteArrayList<>() : emitters;
          while (live.size() >= MAX_STREAMS_PER_USER) {
            evicted.add(live.removeFirst());
          }
          live.add(emitter);
          return live;
        });
    // completing inside compute would re-enter the map through the removal callback
    for (SseEmitter retired : evicted) {
      appCount.decrementAndGet();
      retired.complete();
    }

    Runnable remove = () -> remove(appCount, key, emitter);
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
   * Sends the nudge on every stream the user has open and reports whether any accepted it. The
   * frame id feeds {@code Last-Event-ID}.
   */
  boolean pushNudge(UUID appId, String endUserHash, UUID nudgeId, String text) {
    boolean delivered = false;
    for (SseEmitter emitter : streams.getOrDefault(key(appId, endUserHash), List.of())) {
      try {
        emitter.send(
            SseEmitter.event()
                .name("nudge")
                .id(nudgeId.toString())
                .data(new NudgeFrame(nudgeId, text), MediaType.APPLICATION_JSON));
        delivered = true;
      } catch (IOException | IllegalStateException gone) {
        emitter.completeWithError(gone);
      }
    }
    return delivered;
  }

  // One stream can fire onError and then onCompletion, so only the call that wins the list
  // removal pays back the app budget.
  private void remove(AtomicInteger appCount, String key, SseEmitter emitter) {
    streams.computeIfPresent(
        key,
        (unused, emitters) -> {
          if (emitters.remove(emitter)) {
            appCount.decrementAndGet();
          }
          return emitters.isEmpty() ? null : emitters;
        });
  }

  private static String key(UUID appId, String endUserHash) {
    return appId + ":" + endUserHash;
  }
}
