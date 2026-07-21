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

  // Bounds the whole body, well above the contract's text cap.
  private static final int MAX_BODY_BYTES = 16 * 1024;

  private final AppDirectory appDirectory;
  private final SignatureVerifier signatureVerifier;
  private final MessageRateLimiter rateLimiter;
  private final ConversationRelay relay;
  private final ObjectMapper objectMapper;

  MessagesController(
      AppDirectory appDirectory,
      SignatureVerifier signatureVerifier,
      MessageRateLimiter rateLimiter,
      ConversationRelay relay,
      ObjectMapper objectMapper) {
    this.appDirectory = appDirectory;
    this.signatureVerifier = signatureVerifier;
    this.rateLimiter = rateLimiter;
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
    // Before the signature check, as on the ingest path: the HMAC covers the
    // full body, so a signature over the capped read can never verify and an
    // honest oversized request would die as a misleading 401.
    if (body.length > MAX_BODY_BYTES) {
      return problem(HttpStatus.PAYLOAD_TOO_LARGE, "The body exceeds 16 KB.");
    }
    if (timestamp == null
        || signature == null
        || !signatureVerifier.verify(app.hmacKey(), timestamp, body, signature)) {
      return problem(HttpStatus.UNAUTHORIZED, "The request signature does not verify.");
    }

    MessageBody message = readMessage(body);
    if (message == null) {
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
    if (text == null || text.isBlank() || text.length() > WidgetContract.MESSAGE_TEXT_MAX_CHARS) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "text must be 1 to " + WidgetContract.MESSAGE_TEXT_MAX_CHARS + " characters.");
    }

    // After validation, so a malformed request never spends an honest message's token.
    long retryAfter = rateLimiter.retryAfterSeconds(app.id());
    if (retryAfter > 0) {
      return tooManyMessages(retryAfter);
    }

    // Holdout users are not gated here: the holdout suppresses the proactive
    // nudges that carry the lift signal, and answering a question the user
    // asked first only makes measured lift more conservative. The leak to
    // gate is executing an action for a holdout, once actions land.
    if (!relay.relay(app, new EndUserMessage(id, sessionId, endUserHash, text, message.ref()))) {
      return problem(HttpStatus.TOO_MANY_REQUESTS, "The app's conversation budget is spent.");
    }
    return ResponseEntity.accepted().build();
  }

  /** Parses the body against the message schema, a malformed body as null. */
  private @Nullable MessageBody readMessage(byte[] body) {
    try {
      return objectMapper.readValue(body, MessageBody.class);
    } catch (IOException malformed) {
      return null;
    }
  }

  /** Builds the RFC 9457 problem body every rejection answers with. */
  private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  /** Builds the 429 problem with its Retry-After hint, the one rejection carrying a header. */
  private static ResponseEntity<Object> tooManyMessages(long retryAfterSeconds) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "The app's message rate limit is exhausted."));
  }
}
