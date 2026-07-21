package com.firstrunhq.decisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.firstrunhq.apps.SdkApp;
import com.firstrunhq.v1.AnswerChunk;
import com.firstrunhq.v1.AnswerDone;
import com.firstrunhq.v1.Citation;
import com.firstrunhq.v1.ConversationServiceGrpc;
import com.firstrunhq.v1.ConverseRequest;
import com.firstrunhq.v1.ConverseResponse;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.grpc.client.GrpcChannelFactory;

/** Relays agent Converse frames into the widget's answer frames over an in-process agent. */
class ConversationRelayTests {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID APP = UUID.randomUUID();
  private static final UUID SESSION = UUID.randomUUID();
  private static final String END_USER = "9f86d081884c7d65";
  private static final SdkApp SDK_APP = new SdkApp(APP, TENANT, "hmac", Set.of(), Set.of());

  private final ScriptedAgent agent = new ScriptedAgent();
  private final NudgeStreams streams = mock(NudgeStreams.class);
  private final NudgeContexts contexts = new NudgeContexts();
  private io.grpc.Server server;
  private io.grpc.ManagedChannel channel;
  private GrpcChannelFactory channels;
  private ConversationRelay relay;

  /** Builds one message in the suite's single conversation. */
  private static EndUserMessage message(UUID messageId, String text, @Nullable UUID ref) {
    return new EndUserMessage(messageId, SESSION, END_USER, text, ref);
  }

  @BeforeEach
  void startInProcessAgent() throws Exception {
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name).directExecutor().addService(agent).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    channels = mock(GrpcChannelFactory.class);
    when(channels.createChannel("agent")).thenReturn(channel);
    relay = new ConversationRelay(channels, streams, contexts);
  }

  @AfterEach
  void stopInProcessAgent() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void relaysTokensThenClosesTheAnswerWithItsFullTextAndCitations() {
    UUID messageId = UUID.randomUUID();
    boolean accepted = relay.relay(SDK_APP, message(messageId, "answer", null));

    assertThat(accepted).isTrue();
    // Tokens stream live; the done frame closes the answer carrying the full
    // text, so a widget that dropped tokens to a reload heals from it.
    verify(streams).pushToken(APP, END_USER, new TokenFrame(messageId.toString(), "Use Settings."));
    verify(streams)
        .pushDone(
            APP,
            END_USER,
            new DoneFrame(
                messageId.toString(),
                "Use Settings.",
                List.of(new DoneFrame.Citation("Setup", "https://docs.example.com/setup"))));
  }

  @Test
  void aMidStreamFailureDegradesToTheRetryLine() {
    UUID messageId = UUID.randomUUID();
    relay.relay(SDK_APP, message(messageId, "fail-mid-stream", null));

    // The streamed token is live; the done frame then reconciles to the retry
    // line with no citations, so a truncated answer never reads as complete.
    verify(streams).pushToken(APP, END_USER, new TokenFrame(messageId.toString(), "Half an ans"));
    verify(streams)
        .pushDone(
            APP,
            END_USER,
            new DoneFrame(messageId.toString(), ConversationRelay.FAILURE_TEXT, List.of()));
  }

  @Test
  void opensAnUnreferencedConversationWithoutNudgeContext() {
    // A nudge is on file, but a message with no ref opened the chat organically,
    // so it carries the base context and no intervention or milestone hint.
    contexts.record(
        APP,
        END_USER,
        new NudgeContexts.NudgeContext(
            UUID.randomUUID(), UUID.randomUUID(), "data_source_connected"));

    relay.relay(SDK_APP, message(UUID.randomUUID(), "answer", null));

    var context = agent.context;
    assertThat(context).isNotNull();
    assertThat(context.getTenantId()).isEqualTo(TENANT.toString());
    assertThat(context.getAppId()).isEqualTo(APP.toString());
    assertThat(context.getSessionId()).isEqualTo(SESSION.toString());
    assertThat(context.getConversationId()).isNotEmpty();
    assertThat(context.getInterventionId()).isEmpty();
    assertThat(context.getMilestoneName()).isEmpty();
  }

  @Test
  void reusesOneAgentStreamAcrossASessionsMessages() {
    relay.relay(SDK_APP, message(UUID.randomUUID(), "answer", null));
    relay.relay(SDK_APP, message(UUID.randomUUID(), "answer", null));

    assertThat(agent.conversations).isEqualTo(1);
    assertThat(agent.messages).hasSize(2);
  }

  @Test
  void opensTheConversationWithTheNudgeContextTheRefNames() {
    UUID referredNudge = UUID.randomUUID();
    contexts.record(
        APP,
        END_USER,
        new NudgeContexts.NudgeContext(referredNudge, UUID.randomUUID(), "project_created"));
    contexts.record(
        APP,
        END_USER,
        new NudgeContexts.NudgeContext(
            UUID.randomUUID(), UUID.randomUUID(), "data_source_connected"));

    relay.relay(SDK_APP, message(UUID.randomUUID(), "answer", referredNudge));

    var context = agent.context;
    assertThat(context).isNotNull();
    assertThat(context.getInterventionId()).isEqualTo(referredNudge.toString());
    assertThat(context.getMilestoneName()).isEqualTo("project_created");
  }

  @Test
  void anUnknownRefOpensWithoutNudgeContext() {
    contexts.record(
        APP,
        END_USER,
        new NudgeContexts.NudgeContext(
            UUID.randomUUID(), UUID.randomUUID(), "data_source_connected"));

    relay.relay(SDK_APP, message(UUID.randomUUID(), "answer", UUID.randomUUID()));

    var context = agent.context;
    assertThat(context).isNotNull();
    assertThat(context.getInterventionId()).isEmpty();
  }

  @Test
  void dropsAMessageThatRepeatsAPendingId() {
    UUID messageId = UUID.randomUUID();
    relay.relay(SDK_APP, message(messageId, "hold", null));
    relay.relay(SDK_APP, message(messageId, "hold", null));

    assertThat(agent.messages).hasSize(1);
  }

  @Test
  void dropsAMessageThatRepeatsACompletedId() {
    UUID messageId = UUID.randomUUID();
    relay.relay(SDK_APP, message(messageId, "answer", null));
    relay.relay(SDK_APP, message(messageId, "answer", null));

    assertThat(agent.messages).hasSize(1);
  }

  @Test
  void anAnswerWithoutTokensDegradesToTheRetryLine() {
    UUID messageId = UUID.randomUUID();
    relay.relay(SDK_APP, message(messageId, "fail-silently", null));

    // No tokens streamed, so the answer closes as one retry-line done frame.
    verify(streams, never()).pushToken(eq(APP), eq(END_USER), any());
    verify(streams)
        .pushDone(
            APP,
            END_USER,
            new DoneFrame(messageId.toString(), ConversationRelay.FAILURE_TEXT, List.of()));
  }

  @Test
  void anOverdueAnswerFailsAndScrapsItsConversation() {
    relay = new ConversationRelay(channels, streams, contexts, Duration.ofMillis(50));
    UUID messageId = UUID.randomUUID();
    relay.relay(SDK_APP, message(messageId, "hold", null));

    // The watchdog degrades the held answer to the retry line.
    verify(streams, timeout(5_000))
        .pushDone(
            APP,
            END_USER,
            new DoneFrame(messageId.toString(), ConversationRelay.FAILURE_TEXT, List.of()));

    // The next message reopens a fresh agent stream instead of queueing
    // behind the hung run on the scrapped one.
    relay.relay(SDK_APP, message(UUID.randomUUID(), "answer", null));
    assertThat(agent.conversations).isEqualTo(2);
  }

  @Test
  void aTokenForAnUnknownMessageNeverReachesTheStream() {
    UUID messageId = UUID.randomUUID();
    relay.relay(SDK_APP, message(messageId, "stray-token", null));

    verify(streams, never())
        .pushToken(eq(APP), eq(END_USER), eq(new TokenFrame("unknown", "stray")));
    // The real message streamed nothing, so it degrades to the retry line.
    verify(streams)
        .pushDone(
            APP,
            END_USER,
            new DoneFrame(messageId.toString(), ConversationRelay.FAILURE_TEXT, List.of()));
  }

  /** Answers each user message inline, scripted by the message text. */
  private static final class ScriptedAgent
      extends ConversationServiceGrpc.ConversationServiceImplBase {

    volatile com.firstrunhq.v1.@org.jspecify.annotations.Nullable ConversationContext context;
    volatile int conversations;
    final List<String> messages = new ArrayList<>();

    @Override
    public StreamObserver<ConverseRequest> converse(StreamObserver<ConverseResponse> responses) {
      conversations++;
      return new StreamObserver<>() {
        @Override
        public void onNext(ConverseRequest request) {
          if (request.hasContext()) {
            context = request.getContext();
            return;
          }
          String messageId = request.getUserMessage().getMessageId();
          messages.add(request.getUserMessage().getText());
          switch (request.getUserMessage().getText()) {
            case "answer" -> {
              responses.onNext(
                  ConverseResponse.newBuilder()
                      .setAnswerChunk(
                          AnswerChunk.newBuilder().setMessageId(messageId).setText("Use Settings."))
                      .build());
              responses.onNext(
                  ConverseResponse.newBuilder()
                      .setCitation(
                          Citation.newBuilder()
                              .setMessageId(messageId)
                              .setSourceUrl("https://docs.example.com/setup")
                              .setTitle("Setup")
                              .setSnippet("Use Settings."))
                      .build());
              responses.onNext(
                  ConverseResponse.newBuilder()
                      .setAnswerDone(AnswerDone.newBuilder().setMessageId(messageId))
                      .build());
            }
            case "stray-token" -> {
              responses.onNext(
                  ConverseResponse.newBuilder()
                      .setAnswerChunk(
                          AnswerChunk.newBuilder().setMessageId("unknown").setText("stray"))
                      .build());
              responses.onNext(
                  ConverseResponse.newBuilder()
                      .setAnswerDone(AnswerDone.newBuilder().setMessageId(messageId))
                      .build());
            }
            // hold: no reply, so the answer stays pending
            case "hold" -> {}
            // fail-mid-stream: tokens already streamed, then a failed done
            case "fail-mid-stream" -> {
              responses.onNext(
                  ConverseResponse.newBuilder()
                      .setAnswerChunk(
                          AnswerChunk.newBuilder().setMessageId(messageId).setText("Half an ans"))
                      .build());
              responses.onNext(
                  ConverseResponse.newBuilder()
                      .setAnswerDone(
                          AnswerDone.newBuilder().setMessageId(messageId).setFailed(true))
                      .build());
            }
            // fail-silently: a done frame with no tokens, the agent-side failure shape
            default ->
                responses.onNext(
                    ConverseResponse.newBuilder()
                        .setAnswerDone(AnswerDone.newBuilder().setMessageId(messageId))
                        .build());
          }
        }

        @Override
        public void onError(Throwable failure) {}

        @Override
        public void onCompleted() {
          responses.onCompleted();
        }
      };
    }
  }
}
