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

  /** Fans out over open streams, replaying missed nudges from the buffer. */
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

    // Completing inside compute would re-enter the map through the removal callback.
    for (Stream retired : addCappingPerUser(key, stream)) {
      appCount.decrementAndGet();
      retired.retire();
    }

    Runnable remove = () -> remove(appCount, key, stream);
    stream.emitter.onCompletion(remove);
    stream.emitter.onTimeout(remove);
    stream.emitter.onError(error -> remove.run());

    stream.sendConnectedComment();

    if (lastEventId != null) {
      // The buffer is read after the map insert, so a nudge pushed between the two arrives as
      // both replay and live copy instead of falling between them. The widget drops the copy.
      stream.finishReplay(buffer.after(appId, endUserHash, lastEventId));
    }
    return stream.emitter;
  }

  /**
   * Adds the stream under the user's registry key and returns the oldest streams the per-user cap
   * pushed out. compute serializes with removal per key, so a concurrent removal never strands a
   * live stream.
   */
  private List<Stream> addCappingPerUser(String key, Stream stream) {
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
    return evicted;
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

  /**
   * Sends one live answer frame to every stream the user has open. Answer frames are never
   * buffered: a reconnect reopens live only (api/openapi/stream.yaml), because a replayed
   * half-answer would render as a fresh one.
   */
  void pushAnswerFrame(UUID appId, String endUserHash, AnswerFrame frame) {
    for (Stream stream : streams.getOrDefault(key(appId, endUserHash), List.of())) {
      stream.sendLive(frame.event(), frame);
    }
  }

  /**
   * Drops the stream from the registry. One stream can fire onError and then onCompletion, so only
   * the call that wins the list removal pays back the app budget.
   */
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

  /** Builds the registry key holding one user's open streams for one app. */
  private static String key(UUID appId, String endUserHash) {
    return appId + ":" + endUserHash;
  }

  /**
   * One open emitter behind a replay gate. A nudge pushed while a reconnect's replay drains waits
   * for it to finish, so a newer live frame never precedes an older missed one, an inversion the
   * widget cannot repair because it deduplicates but never reorders.
   */
  private static final class Stream {

    final SseEmitter emitter;
    private final List<NudgeFrame> heldByReplay = new ArrayList<>();
    private boolean replaying;

    /** Gates a reconnecting stream on its replay. A fresh stream starts open. */
    Stream(SseEmitter emitter, boolean replaying) {
      this.emitter = emitter;
      this.replaying = replaying;
    }

    /**
     * Sends the frame, or holds it while a replay drains. A held frame is not yet on the wire, so
     * it reports failure: only a durable buffer or a real send lets the caller claim the nudge, and
     * a claim on a frame a dying stream never flushes would drop it with no retry.
     */
    synchronized boolean deliver(NudgeFrame frame) {
      if (replaying) {
        heldByReplay.add(frame);
        return false;
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

    /** Commits the response headers, so EventSource opens now, not on the first nudge. */
    synchronized void sendConnectedComment() {
      try {
        emitter.send(SseEmitter.event().comment("connected"));
      } catch (IOException | IllegalStateException gone) {
        emitter.completeWithError(gone);
      }
    }

    /** Sends one unbuffered frame, closing the stream when the tab is gone. */
    synchronized void sendLive(String eventName, Object frame) {
      try {
        emitter.send(SseEmitter.event().name(eventName).data(frame, MediaType.APPLICATION_JSON));
      } catch (IOException | IllegalStateException gone) {
        emitter.completeWithError(gone);
      }
    }

    /** Sends one nudge frame with its replay-cursor id, reporting whether it left. */
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
