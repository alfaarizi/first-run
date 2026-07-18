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

  private final Map<String, List<Stream>> streams = new ConcurrentHashMap<>();
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

    Stream stream = new Stream(new SseEmitter(STREAM_TIMEOUT.toMillis()), lastEventId != null);
    String key = key(appId, endUserHash);

    // compute serializes with removal per key, so a concurrent removal never strands a live stream
    List<Stream> evicted = new ArrayList<>();
    streams.compute(
        key,
        (unused, open) -> {
          List<Stream> live = open == null ? new CopyOnWriteArrayList<>() : open;
          while (live.size() >= MAX_STREAMS_PER_USER) {
            evicted.add(live.removeFirst());
          }
          live.add(stream);
          return live;
        });

    // completing inside compute would re-enter the map through the removal callback
    for (Stream retired : evicted) {
      appCount.decrementAndGet();
      retired.retire();
    }

    Runnable remove = () -> remove(appCount, key, stream);
    stream.emitter.onCompletion(remove);
    stream.emitter.onTimeout(remove);
    stream.emitter.onError(error -> remove.run());

    // A first frame commits the response headers, so EventSource opens now, not on the first nudge.
    try {
      stream.emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException | IllegalStateException gone) {
      stream.emitter.completeWithError(gone);
    }

    if (lastEventId != null) {
      // The buffer is read after the map insert, so a nudge pushed between the two arrives as
      // both replay and live copy instead of falling between them. The widget drops the copy.
      stream.finishReplay(buffer.after(appId, endUserHash, lastEventId));
    }
    return stream.emitter;
  }

  /**
   * Buffers the nudge for reconnect replay, sends it on every stream the user has open, and reports
   * whether the buffer or any stream accepted it. The frame id feeds {@code Last-Event-ID}.
   */
  boolean pushNudge(UUID appId, String endUserHash, UUID nudgeId, String text) {
    NudgeFrame frame = new NudgeFrame(nudgeId, text);

    // Buffered before the fan-out, so a stream registering between the two still replays it.
    boolean accepted = buffer.append(appId, endUserHash, frame);

    for (Stream stream : streams.getOrDefault(key(appId, endUserHash), List.of())) {
      accepted |= stream.deliver(frame);
    }
    return accepted;
  }

  // One stream can fire onError and then onCompletion, so only the call that wins the list
  // removal pays back the app budget.
  private void remove(AtomicInteger appCount, String key, Stream stream) {
    streams.computeIfPresent(
        key,
        (unused, open) -> {
          if (open.remove(stream)) {
            appCount.decrementAndGet();
          }
          return open.isEmpty() ? null : open;
        });
  }

  private static String key(UUID appId, String endUserHash) {
    return appId + ":" + endUserHash;
  }

  /**
   * One open emitter behind a replay gate. A nudge pushed while a reconnect's replay drains waits
   * until the replay finishes, so the stream never carries a newer live frame before an older
   * missed one, which the widget could not repair because it deduplicates but never reorders.
   */
  private static final class Stream {

    final SseEmitter emitter;
    private final List<NudgeFrame> heldByReplay = new ArrayList<>();
    private boolean replaying;

    Stream(SseEmitter emitter, boolean replaying) {
      this.emitter = emitter;
      this.replaying = replaying;
    }

    /** Sends the frame, or holds it while a replay drains, reporting whether the stream took it. */
    synchronized boolean deliver(NudgeFrame frame) {
      if (replaying) {
        heldByReplay.add(frame);
        return true;
      }
      return send(frame);
    }

    /** Sends the missed frames, then the pushes they held up, and lifts the gate. */
    synchronized void finishReplay(List<NudgeFrame> missed) {
      for (NudgeFrame frame : missed) {
        send(frame);
      }
      for (NudgeFrame frame : heldByReplay) {
        send(frame);
      }
      heldByReplay.clear();
      replaying = false;
    }

    /**
     * A plain close makes EventSource reconnect, evicting the next stream in an endless rotation,
     * so this frame tells the widget to stay shut.
     */
    synchronized void retire() {
      try {
        emitter.send(SseEmitter.event().name("retired").data("{}"));
      } catch (IOException | IllegalStateException gone) {
        // the tab is already gone, so there is no client left to reconnect
      }
      emitter.complete();
    }

    private boolean send(NudgeFrame frame) {
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
  }
}
