package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.apps.AppDirectory;
import com.firstrunhq.apps.SdkApp;
import com.firstrunhq.apps.SignatureVerifier;
import com.firstrunhq.apps.WidgetContract;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts end-user chat messages and hands them to the conversation relay. The answer streams back
 * over the user's push stream, never this response.
 */
@RestController
class MessagesController {

  // Endpoint contract from api/openapi/messages.yaml, read here and mirrored by the CORS policy.
  static final String PATH = "/v1/messages";

  // The contract caps text at 2000 characters; this bounds the whole body.
  private static final int MAX_BODY_BYTES = 16 * 1024;
  private static final int MAX_TEXT_CHARS = 2_000;

  private final AppDirectory appDirectory;
  private final SignatureVerifier signatureVerifier;
  private final ConversationRelay relay;
  private final ObjectMapper objectMapper;

  MessagesController(
      AppDirectory appDirectory,
      SignatureVerifier signatureVerifier,
      ConversationRelay relay,
      ObjectMapper objectMapper) {
    this.appDirectory = appDirectory;
    this.signatureVerifier = signatureVerifier;
    this.relay = relay;
    this.objectMapper = objectMapper;
  }

  /**
   * Authenticates the message and forwards it. The authentication headers are optional so a missing
   * one answers as its own check's failure (401), not a framework 400.
   */
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Object> sendMessage(
      @RequestHeader(value = WidgetContract.SDK_KEY_HEADER, required = false)
          @Nullable String sdkKey,
      @RequestHeader(value = WidgetContract.TIMESTAMP_HEADER, required = false)
          @Nullable String timestamp,
      @RequestHeader(value = WidgetContract.SIGNATURE_HEADER, required = false)
          @Nullable String signature,
      @RequestHeader(value = HttpHeaders.ORIGIN, required = false) @Nullable String origin,
      HttpServletRequest request)
      throws IOException {
    // Reading at most one byte past the limit bounds the buffer, whatever Content-Length claims.
    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);

    SdkApp app = sdkKey == null ? null : appDirectory.findBySdkKey(sdkKey).orElse(null);
    if (app == null) {
      return problem(HttpStatus.UNAUTHORIZED, "No app owns this SDK key.");
    }
    if (origin != null && !app.allowedOrigins().contains(origin)) {
      return problem(HttpStatus.FORBIDDEN, "The origin is not on the app's allowed origins.");
    }
    if (timestamp == null
        || signature == null
        || !signatureVerifier.verify(app.hmacKey(), timestamp, body, signature)) {
      return problem(HttpStatus.UNAUTHORIZED, "The request signature does not verify.");
    }
    if (body.length > MAX_BODY_BYTES) {
      return problem(HttpStatus.BAD_REQUEST, "The body exceeds " + MAX_BODY_BYTES + " bytes.");
    }

    MessageBody message;
    try {
      message = objectMapper.readValue(body, MessageBody.class);
    } catch (IOException malformed) {
      return problem(HttpStatus.BAD_REQUEST, "The body is not the message schema.");
    }
    UUID id = message.id();
    UUID sessionId = message.sessionId();
    String endUserHash = message.endUserHash();
    String text = message.text();
    if (id == null || sessionId == null) {
      return problem(HttpStatus.BAD_REQUEST, "id and session_id must be UUIDs.");
    }
    if (endUserHash == null
        || endUserHash.isBlank()
        || endUserHash.length() > WidgetContract.END_USER_HASH_MAX_CHARS) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "end_user_hash must be 1 to " + WidgetContract.END_USER_HASH_MAX_CHARS + " characters.");
    }
    if (text == null || text.isBlank() || text.length() > MAX_TEXT_CHARS) {
      return problem(
          HttpStatus.BAD_REQUEST, "text must be 1 to " + MAX_TEXT_CHARS + " characters.");
    }

    // Holdout users are not gated here: the holdout suppresses the proactive
    // nudges that carry the lift signal, and answering a question the user
    // asked first only makes measured lift more conservative. The leak to
    // gate is executing an action for a holdout, once actions land.
    if (!relay.relay(app, id, sessionId, endUserHash, text, message.ref())) {
      return problem(HttpStatus.TOO_MANY_REQUESTS, "The app's conversation budget is spent.");
    }
    return ResponseEntity.accepted().build();
  }

  private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(status, detail));
  }
}
