package com.firstrunhq.decisioning.internal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.firstrunhq.apps.SdkApp;
import com.firstrunhq.v1.ConversationContext;
import com.firstrunhq.v1.ConversationServiceGrpc;
import com.firstrunhq.v1.ConverseRequest;
import com.firstrunhq.v1.ConverseResponse;
import com.firstrunhq.v1.UserMessage;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * Holds one agent Converse stream per conversation and turns its frames into the widget's answer
 * frames. A failed or overdue answer degrades to an honest retry line: the widget must never hang
 * on a question, and a wrong answer is worse than none.
 */
@Component
class ConversationRelay {

  static final String FAILURE_TEXT = "Something went wrong finding an answer. Try again.";

  // Streaming has no unary deadline, so a watchdog bounds each answer instead
  // (the retry-with-backoff treatment fits unary calls like Reindex, not a
  // held-open bidi stream).
  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);

  // Matches the session idle window, so a conversation dies with its session.
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

  // The signing key ships in the page, so anyone can mint valid messages. The
  // budget keeps one flooded app from pinning unbounded agent streams.
  private static final int MAX_CONVERSATIONS_PER_APP = 1_000;

  private static final NoArgGenerator UUID_V7 = Generators.timeBasedEpochRandomGenerator();
  private static final Logger log = LoggerFactory.getLogger(ConversationRelay.class);

  private final ConversationServiceGrpc.ConversationServiceStub stub;
  private final NudgeStreams streams;
  private final NudgeContexts contexts;
  private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> appCounts = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;

  ConversationRelay(GrpcChannelFactory channels, NudgeStreams streams, NudgeContexts contexts) {
    this.stub = ConversationServiceGrpc.newStub(channels.createChannel("agent"));
    this.streams = streams;
    this.contexts = contexts;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable ->
                Thread.ofPlatform().daemon().name("conversation-relay").unstarted(runnable));
    scheduler.scheduleAtFixedRate(
        this::evictIdle,
        SWEEP_INTERVAL.toMillis(),
        SWEEP_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  /** Forwards one message, or reports false when the app's conversation budget is spent. */
  boolean relay(SdkApp app, UUID messageId, UUID sessionId, String endUserHash, String text) {
    String key = app.id() + ":" + endUserHash + ":" + sessionId;
    Conversation conversation = conversations.get(key);
    if (conversation == null) {
      AtomicInteger count = appCounts.computeIfAbsent(app.id(), id -> new AtomicInteger());
      if (count.incrementAndGet() > MAX_CONVERSATIONS_PER_APP) {
        count.decrementAndGet();
        return false;
      }
      conversation =
          conversations.computeIfAbsent(key, unused -> open(key, app, sessionId, endUserHash));
      // Lost the creation race: give back the reservation the winner kept.
      if (!conversation.opening.compareAndSet(true, false)) {
        count.decrementAndGet();
      }
    }
    conversation.send(messageId, text);
    return true;
  }

  private Conversation open(String key, SdkApp app, UUID sessionId, String endUserHash) {
    NudgeContexts.NudgeContext nudge = contexts.latest(app.id(), endUserHash).orElse(null);
    ConversationContext.Builder context =
        ConversationContext.newBuilder()
            .setConversationId(UUID_V7.generate().toString())
            .setTenantId(app.tenantId().toString())
            .setAppId(app.id().toString())
            .setEndUserHash(endUserHash)
            .setSessionId(sessionId.toString());
    if (nudge != null) {
      context
          .setInterventionId(nudge.nudgeId().toString())
          .setMilestoneId(nudge.milestoneId().toString())
          .setMilestoneName(nudge.milestoneName());
    }
    Conversation conversation = new Conversation(key, app.id(), endUserHash);
    conversation.start(context.build());
    return conversation;
  }

  private void evictIdle() {
    Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
    conversations.values().stream()
        .filter(conversation -> conversation.lastActivity.isBefore(cutoff))
        .forEach(Conversation::close);
  }

  private void remove(Conversation conversation) {
    if (conversations.remove(conversation.key, conversation)) {
      appCounts.getOrDefault(conversation.appId, new AtomicInteger()).decrementAndGet();
    }
  }

  /** One agent stream, its in-flight answers, and their watchdogs. */
  private final class Conversation implements StreamObserver<ConverseResponse> {

    final String key;
    final UUID appId;
    final AtomicBoolean opening = new AtomicBoolean(true);
    volatile Instant lastActivity = Instant.now();

    private final String endUserHash;
    private final Map<String, PendingAnswer> pending = new ConcurrentHashMap<>();

    @SuppressWarnings("NullAway.Init") // assigned once in start, before any use
    private StreamObserver<ConverseRequest> requests;

    Conversation(String key, UUID appId, String endUserHash) {
      this.key = key;
      this.appId = appId;
      this.endUserHash = endUserHash;
    }

    void start(ConversationContext context) {
      requests = stub.converse(this);
      requests.onNext(ConverseRequest.newBuilder().setContext(context).build());
    }

    synchronized void send(UUID messageId, String text) {
      lastActivity = Instant.now();
      String id = messageId.toString();
      pending.put(
          id,
          new PendingAnswer(
              scheduler.schedule(
                  () -> finish(id, true), ANSWER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));
      try {
        requests.onNext(
            ConverseRequest.newBuilder()
                .setUserMessage(UserMessage.newBuilder().setMessageId(id).setText(text))
                .build());
      } catch (IllegalStateException dead) {
        // The stream died between open and send; the user still gets the retry line.
        finish(id, true);
        remove(this);
      }
    }

    @Override
    public void onNext(ConverseResponse response) {
      lastActivity = Instant.now();
      switch (response.getFrameCase()) {
        case ANSWER_CHUNK -> {
          PendingAnswer answer = pending.get(response.getAnswerChunk().getMessageId());
          if (answer != null) {
            answer.streamedTokens = true;
            streams.pushAnswerFrame(
                appId, endUserHash, "token", new TokenFrame(response.getAnswerChunk().getText()));
          }
        }
        case CITATION -> {
          PendingAnswer answer = pending.get(response.getCitation().getMessageId());
          if (answer != null) {
            var citation = response.getCitation();
            String title =
                citation.getTitle().isEmpty() ? citation.getSourceUrl() : citation.getTitle();
            answer.citations.add(new DoneFrame.Citation(title, citation.getSourceUrl()));
          }
        }
        case ANSWER_DONE -> finish(response.getAnswerDone().getMessageId(), false);
        // Proposals wait for the action registry; nothing renders them yet.
        case ACTION_PROPOSAL, FRAME_NOT_SET -> {}
      }
    }

    @Override
    public void onError(Throwable failure) {
      log.warn("agent conversation stream failed", failure);
      failAllPending();
      remove(this);
    }

    @Override
    public void onCompleted() {
      failAllPending();
      remove(this);
    }

    void close() {
      try {
        requests.onCompleted();
      } catch (IllegalStateException alreadyClosed) {
        // the observer half-closed first, which is the state close wants
      }
      failAllPending();
      remove(this);
    }

    /**
     * Closes one answer exactly once: the agent's done frame and the watchdog race, and the loser
     * finds the entry gone. An answer with no tokens failed agent-side, so it degrades to the retry
     * line instead of an empty bubble.
     */
    private void finish(String messageId, boolean timedOut) {
      PendingAnswer answer = pending.remove(messageId);
      if (answer == null) {
        return;
      }
      answer.watchdog.cancel(false);
      if (timedOut || !answer.streamedTokens) {
        streams.pushAnswerFrame(appId, endUserHash, "token", new TokenFrame(FAILURE_TEXT));
        streams.pushAnswerFrame(appId, endUserHash, "done", new DoneFrame(List.of()));
        return;
      }
      streams.pushAnswerFrame(appId, endUserHash, "done", new DoneFrame(answer.citations));
    }

    private void failAllPending() {
      pending.keySet().forEach(messageId -> finish(messageId, true));
    }
  }

  /** Collects one answer as it streams. */
  private static final class PendingAnswer {

    final List<DoneFrame.Citation> citations = new CopyOnWriteArrayList<>();
    final ScheduledFuture<?> watchdog;
    volatile boolean streamedTokens;

    PendingAnswer(ScheduledFuture<?> watchdog) {
      this.watchdog = watchdog;
    }
  }
}
