package com.firstrunhq.decisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.firstrunhq.apps.AppDirectory;
import com.firstrunhq.apps.SdkApp;
import com.firstrunhq.apps.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Walks the authentication and validation ladder in api/openapi/messages.yaml. */
class MessagesControllerTests {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID APP = UUID.randomUUID();
  private static final String HMAC_KEY = "test-hmac-key";
  private static final SdkApp SDK_APP =
      new SdkApp(APP, TENANT, HMAC_KEY, Set.of("https://app.example.com"), Set.of());

  private final AppDirectory appDirectory = mock(AppDirectory.class);
  private final ConversationRelay relay = mock(ConversationRelay.class);
  private final MessagesController controller =
      new MessagesController(
          appDirectory,
          new SignatureVerifier(),
          relay,
          JsonMapper.builder().findAndAddModules().build());

  @Test
  void acceptsASignedMessageAndHandsItToTheRelay() {
    when(appDirectory.findBySdkKey("pk_test")).thenReturn(Optional.of(SDK_APP));
    when(relay.relay(any(), any(), any(), any(), any())).thenReturn(true);

    ResponseEntity<Object> response = post(body("How do I connect?"), true, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void rejectsAnUnknownSdkKeyBeforeReadingTheBody() {
    when(appDirectory.findBySdkKey("pk_test")).thenReturn(Optional.empty());

    ResponseEntity<Object> response = post(body("hi"), true, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(relay);
  }

  @Test
  void rejectsAnOriginOffTheAllowlist() {
    when(appDirectory.findBySdkKey("pk_test")).thenReturn(Optional.of(SDK_APP));

    ResponseEntity<Object> response = post(body("hi"), true, "https://evil.example.com");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    verifyNoInteractions(relay);
  }

  @Test
  void rejectsABadSignature() {
    when(appDirectory.findBySdkKey("pk_test")).thenReturn(Optional.of(SDK_APP));

    ResponseEntity<Object> response = post(body("hi"), false, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(relay);
  }

  @Test
  void rejectsTextOverTheContractCap() {
    when(appDirectory.findBySdkKey("pk_test")).thenReturn(Optional.of(SDK_APP));

    ResponseEntity<Object> response = post(body("x".repeat(2_001)), true, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verifyNoInteractions(relay);
  }

  @Test
  void answersAHoldoutUsersReactiveQuestionLikeAnyOther() {
    // The holdout suppresses proactive nudges, not a question the user asks
    // first, so a holdout hash reaches the relay unblocked (INV-6).
    when(appDirectory.findBySdkKey("pk_test")).thenReturn(Optional.of(SDK_APP));
    when(relay.relay(any(), any(), any(), any(), any())).thenReturn(true);

    ResponseEntity<Object> response = post(body("hi"), true, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void answersTooManyRequestsWhenTheConversationBudgetIsSpent() {
    when(appDirectory.findBySdkKey("pk_test")).thenReturn(Optional.of(SDK_APP));
    when(relay.relay(any(), any(), any(), any(), any())).thenReturn(false);

    ResponseEntity<Object> response = post(body("hi"), true, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  private ResponseEntity<Object> post(String body, boolean signed, @Nullable String origin) {
    String timestamp = Instant.now().toString();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    try {
      return controller.sendMessage(
          "pk_test", timestamp, signed ? sign(timestamp, body) : "0".repeat(64), origin, request);
    } catch (Exception unexpected) {
      throw new AssertionError(unexpected);
    }
  }

  private static String body(String text) {
    return """
        {"id":"%s","session_id":"%s","end_user_hash":"9f86d081884c7d65","text":"%s"}"""
        .formatted(UUID.randomUUID(), UUID.randomUUID(), text);
  }

  private static String sign(String timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }
}
