package com.firstrunhq.decisioning.internal;

import com.firstrunhq.apps.AppDirectory;
import com.firstrunhq.apps.SdkApp;
import com.firstrunhq.apps.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class StreamController {

  // Endpoint contract from api/openapi/stream.yaml, read here and mirrored by the CORS policy.
  static final String PATH = "/v1/stream";

  private static final int MAX_END_USER_HASH_LENGTH = 128;
  private static final int MAX_LAST_EVENT_ID_LENGTH = 64;

  private final AppDirectory appDirectory;
  private final SignatureVerifier signatureVerifier;
  private final NudgeStreams streams;

  StreamController(
      AppDirectory appDirectory, SignatureVerifier signatureVerifier, NudgeStreams streams) {
    this.appDirectory = appDirectory;
    this.signatureVerifier = signatureVerifier;
    this.streams = streams;
  }

  /**
   * Opens the server-push stream for one end user. The signature binds the user hash, so one signed
   * URL cannot subscribe another user. A reconnect carrying the last seen frame id replays the
   * nudges buffered while the stream was down.
   *
   * <p>A same-origin {@code EventSource} carries no {@code Origin} header, so the origin gate runs
   * only when one arrives and the signature carries the rest.
   */
  @GetMapping(path = PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> connect(
      @RequestParam(value = "key", required = false) @Nullable String key,
      @RequestParam(value = "end_user_hash", required = false) @Nullable String endUserHash,
      @RequestParam(value = "ts", required = false) @Nullable String timestamp,
      @RequestParam(value = "sig", required = false) @Nullable String signature,
      @RequestParam(value = "last_event_id", required = false) @Nullable String lastEventIdParam,
      @RequestHeader(value = "Last-Event-ID", required = false) @Nullable String lastEventIdHeader,
      @RequestHeader(value = HttpHeaders.ORIGIN, required = false) @Nullable String origin) {
    SdkApp app = key == null ? null : appDirectory.findBySdkKey(key).orElse(null);
    if (app == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    if (origin != null && !app.allowedOrigins().contains(origin)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    // The length cap from the ingest contract also bounds the stream registry's keys.
    if (endUserHash == null
        || endUserHash.length() > MAX_END_USER_HASH_LENGTH
        || timestamp == null
        || signature == null
        || !signatureVerifier.verify(
            app.hmacKey(), timestamp, endUserHash.getBytes(StandardCharsets.UTF_8), signature)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    // The browser's automatic reconnect carries the header while the widget's manual reopen
    // carries the parameter, and the header is the later of the two, so it wins.
    String lastEventId = lastEventIdHeader != null ? lastEventIdHeader : lastEventIdParam;
    if (lastEventId != null && lastEventId.length() > MAX_LAST_EVENT_ID_LENGTH) {
      // An overlong cursor would replay the whole buffer, so it reads as absent.
      lastEventId = null;
    }
    SseEmitter stream = streams.register(app.id(), endUserHash, lastEventId);
    if (stream == null) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }
    return ResponseEntity.ok(stream);
  }
}
