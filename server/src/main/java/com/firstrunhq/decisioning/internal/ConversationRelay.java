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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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

  // Streaming has no unary deadline, so a watchdog bounds each answer.
  // Retry-with-backoff fits unary calls like Reindex, not a held-open stream.
  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);

  // Matches the session idle window, so a conversation dies with its session.
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

  // The signing key ships in the page, so anyone can mint valid messages. The
  // budget keeps one flooded app from pinning unbounded agent streams.
  private static final int MAX_CONVERSATIONS_PER_APP = 1_000;

  // The widget never reuses an id, so remembering a bounded tail of finished
  // ones is enough to drop any repeat, not only an in-flight one.
  private static final int MAX_COMPLETED_IDS = 256;

  private static final NoArgGenerator UUID_V7 = Generators.timeBasedEpochRandomGenerator();
  private static final Logger log = LoggerFactory.getLogger(ConversationRelay.class);

  private final ConversationServiceGrpc.ConversationServiceStub stub;
  private final NudgeStreams streams;
  private final NudgeContexts contexts;
  private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> appCounts = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler;

  /** Opens the agent channel and starts the idle-conversation sweeper. */
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
  boolean relay(SdkApp app, EndUserMessage message) {
    String key = app.id() + ":" + message.endUserHash() + ":" + message.sessionId();
    Conversation conversation = conversations.get(key);
    if (conversation == null) {
      conversation = openWithinBudget(key, app, message);
      if (conversation == null) {
        return false;
      }
    }
    conversation.send(message.messageId(), message.text());
    return true;
  }

  /**
   * Opens the keyed conversation under the app's budget, or returns null when the budget is spent.
   * The count is reserved before the open and paid back on failure or on losing the creation race,
   * so it always matches the conversations actually held.
   */
  private @Nullable Conversation openWithinBudget(String key, SdkApp app, EndUserMessage message) {
    AtomicInteger count = appCounts.computeIfAbsent(app.id(), id -> new AtomicInteger());
    if (count.incrementAndGet() > MAX_CONVERSATIONS_PER_APP) {
      count.decrementAndGet();
      return null;
    }
    Conversation conversation;
    try {
      conversation = conversations.computeIfAbsent(key, unused -> open(key, app, message));
    } catch (RuntimeException openFailed) {
      // The reservation must not outlive a conversation that never opened,
      // or agent outages ratchet the app toward a permanent 429.
      count.decrementAndGet();
      throw openFailed;
    }
    // Lost the creation race: give back the reservation the winner kept.
    if (!conversation.opening.compareAndSet(true, false)) {
      count.decrementAndGet();
    }
    return conversation;
  }

  private Conversation open(String key, SdkApp app, EndUserMessage message) {
    // The ref names the nudge that opened the chat. An unknown ref yields no
    // context, because a guessed milestone hint corrupts attribution.
    UUID ref = message.ref();
    NudgeContexts.NudgeContext nudge =
        (ref != null
                ? contexts.find(app.id(), message.endUserHash(), ref)
                : contexts.latest(app.id(), message.endUserHash()))
            .orElse(null);
    ConversationContext.Builder context =
        ConversationContext.newBuilder()
            .setConversationId(UUID_V7.generate().toString())
            .setTenantId(app.tenantId().toString())
            .setAppId(app.id().toString())
            .setEndUserHash(message.endUserHash())
            .setSessionId(message.sessionId().toString());
    if (nudge != null) {
      context
          .setInterventionId(nudge.nudgeId().toString())
          .setMilestoneId(nudge.milestoneId().toString())
          .setMilestoneName(nudge.milestoneName());
    }
    Conversation conversation = new Conversation(key, app.id(), message.endUserHash());
    conversation.start(context.build());
    return conversation;
  }

  /** Closes every conversation idle past the timeout, run by the sweeper. */
  private void evictIdle() {
    Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
    conversations.values().stream()
        .filter(conversation -> conversation.lastActivity.isBefore(cutoff))
        .forEach(Conversation::close);
  }

  /** Drops the conversation from the registry, paying its budget back exactly once. */
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

    // Finished ids, insertion-order bounded. Synchronized on itself because
    // send and finish run on different threads.
    private final Set<String> completed =
        Collections.newSetFromMap(
            new LinkedHashMap<>(16, 0.75f, false) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_COMPLETED_IDS;
              }
            });

    @SuppressWarnings("NullAway.Init") // assigned once in start, before any use
    private StreamObserver<ConverseRequest> requests;

    Conversation(String key, UUID appId, String endUserHash) {
      this.key = key;
      this.appId = appId;
      this.endUserHash = endUserHash;
    }

    /** Opens the agent stream, sending the contract's context frame first. */
    void start(ConversationContext context) {
      requests = stub.converse(this);
      requests.onNext(ConverseRequest.newBuilder().setContext(context).build());
    }

    /** Forwards one user message, arming its watchdog and dropping client retries. */
    synchronized void send(UUID messageId, String text) {
      lastActivity = Instant.now();
      String id = messageId.toString();
      // A repeated id, pending or already answered, is a client retry. The
      // contract drops it, so it never displaces an answer or spends a
      // second model call.
      if (pending.containsKey(id)) {
        return;
      }
      synchronized (completed) {
        if (completed.contains(id)) {
          return;
        }
      }
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
        // The stream died between open and send. The user still gets the retry line.
        finish(id, true);
        remove(this);
      }
    }

    /** Routes one agent frame to the user's streams by its message id. */
    @Override
    public void onNext(ConverseResponse response) {
      lastActivity = Instant.now();
      switch (response.getFrameCase()) {
        case ANSWER_CHUNK -> {
          var chunk = response.getAnswerChunk();
          PendingAnswer answer = pending.get(chunk.getMessageId());
          if (answer != null) {
            answer.streamedTokens = true;
            streams.pushAnswerFrame(
                appId, endUserHash, new TokenFrame(chunk.getMessageId(), chunk.getText()));
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
        case ANSWER_DONE -> {
          var done = response.getAnswerDone();
          finish(done.getMessageId(), done.getFailed());
        }
        // Proposals wait for the action registry. Nothing renders them yet.
        case ACTION_PROPOSAL, FRAME_NOT_SET -> {}
      }
    }

    /** Fails every in-flight answer when the agent stream dies. */
    @Override
    public void onError(Throwable failure) {
      log.warn("agent conversation stream failed", failure);
      failAllPending();
      remove(this);
    }

    /** Retires the conversation when the agent closes its side. */
    @Override
    public void onCompleted() {
      failAllPending();
      remove(this);
    }

    /**
     * Half-closes toward the agent and retires the conversation. Synchronized like send, because
     * the gRPC observer is not thread-safe and the sweeper must never race onCompleted against a
     * request thread's onNext.
     */
    synchronized void close() {
      try {
        requests.onCompleted();
      } catch (IllegalStateException alreadyClosed) {
        // The observer half-closed first, which is the state close wants.
      }
      failAllPending();
      remove(this);
    }

    /**
     * Closes one answer exactly once: the agent's done frame and the watchdog race, and the loser
     * finds the entry gone. A timed-out, agent-failed, or token-less answer degrades to the retry
     * line, so a truncated answer never closes as a complete one.
     */
    private void finish(String messageId, boolean failed) {
      PendingAnswer answer = pending.remove(messageId);
      if (answer == null) {
        return;
      }
      answer.watchdog.cancel(false);
      synchronized (completed) {
        completed.add(messageId);
      }
      if (failed || !answer.streamedTokens) {
        streams.pushAnswerFrame(appId, endUserHash, new TokenFrame(messageId, FAILURE_TEXT));
        streams.pushAnswerFrame(appId, endUserHash, new DoneFrame(messageId, List.of()));
        return;
      }
      streams.pushAnswerFrame(
          appId, endUserHash, new DoneFrame(messageId, List.copyOf(answer.citations)));
    }

    /** Closes every in-flight answer as failed, each yielding the retry line. */
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
