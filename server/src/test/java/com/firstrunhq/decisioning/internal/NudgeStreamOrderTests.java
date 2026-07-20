package com.firstrunhq.decisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.firstrunhq.apps.AppDirectory;
import com.firstrunhq.apps.SdkApp;
import com.firstrunhq.apps.SignatureVerifier;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins the replay gate: a nudge pushed while a reconnect's replay drains arrives after the missed
 * frames, never before, because the widget drops a duplicate but cannot reorder an inversion. The
 * push fires from inside the mocked buffer read, the exact window the gate exists for.
 */
class NudgeStreamOrderTests {

  private final NudgeReplayBuffer buffer = mock(NudgeReplayBuffer.class);
  private final AnswerReplayBuffer answers = mock(AnswerReplayBuffer.class);
  private final NudgeStreams streams = new NudgeStreams(buffer, answers);

  @Test
  void holdsALivePushBehindTheReplayItArrivedDuring() throws Exception {
    UUID appId = UUID.randomUUID();
    UUID missed = UUID.randomUUID();
    UUID live = UUID.randomUUID();

    when(buffer.append(any(), any(), any())).thenReturn(true);
    when(buffer.after(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              streams.pushNudge(appId, "user-order", live, "live");
              return List.of(new NudgeFrame(missed, "missed"));
            });

    AppDirectory directory = mock(AppDirectory.class);
    SignatureVerifier verifier = mock(SignatureVerifier.class);
    when(directory.findBySdkKey("key_order"))
        .thenReturn(Optional.of(new SdkApp(appId, UUID.randomUUID(), "hmac", Set.of(), Set.of())));
    when(verifier.verify(any(), any(), any(), any())).thenReturn(true);

    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new StreamController(directory, verifier, streams)).build();
    MvcResult result =
        mvc.perform(
                get("/v1/stream")
                    .queryParam("key", "key_order")
                    .queryParam("end_user_hash", "user-order")
                    .queryParam("ts", "2026-07-18T00:00:00Z")
                    .queryParam("sig", "f".repeat(64))
                    .queryParam("last_event_id", "earliest"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body.indexOf(missed.toString()))
        .isNotNegative()
        .isLessThan(body.indexOf(live.toString()));
  }
}
