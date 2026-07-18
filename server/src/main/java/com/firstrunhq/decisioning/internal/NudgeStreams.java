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
  private final NudgeReplayBuffer buffer;

  NudgeStreams(NudgeReplayBuffer buffer) {
    this.buffer = buffer;
  }

  /**
   * Opens a stream for the user, replaying the frames missed since {@code lastEventId}, or returns
   * null when the app's budget is spent.
   */
  @Nullable SseEmitter register(UUID appId, String endUserHash, @Nullable String lastEventId) {
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
      try {
        // A plain close makes EventSource reconnect, evicting the next stream in an
        // endless rotation, so this frame tells the widget to stay shut.
        retired.send(SseEmitter.event().name("retired").data("{}"));
      } catch (IOException | IllegalStateException gone) {
        // the tab is already gone, so there is no client left to reconnect
      }
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

    if (lastEventId != null) {
      // The buffer is read after the map insert, so a nudge pushed between the two arrives as
      // both replay and live copy instead of falling between them. The widget drops the copy.
      for (NudgeFrame missed : buffer.after(appId, endUserHash, lastEventId)) {
        sendNudge(emitter, missed);
      }
    }
    return emitter;
  }

  /**
   * Buffers the nudge for reconnect replay, sends it on every stream the user has open, and reports
   * whether the buffer or any stream accepted it. The frame id feeds {@code Last-Event-ID}.
   */
  boolean pushNudge(UUID appId, String endUserHash, UUID nudgeId, String text) {
    NudgeFrame frame = new NudgeFrame(nudgeId, text);

    // Buffered before the fan-out, so a stream registering between the two still replays it.
    boolean accepted = buffer.append(appId, endUserHash, frame);

    for (SseEmitter emitter : streams.getOrDefault(key(appId, endUserHash), List.of())) {
      accepted |= sendNudge(emitter, frame);
    }
    return accepted;
  }

  private static boolean sendNudge(SseEmitter emitter, NudgeFrame frame) {
    try {
      emitter.send(
          SseEmitter.event()
              .name("nudge")
              .id(frame.id().toString())
              .data(frame, MediaType.APPLICATION_JSON));
      return true;
    } catch (IOException | IllegalStateException gone) {
      emitter.completeWithError(gone);
      return false;
    }
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
