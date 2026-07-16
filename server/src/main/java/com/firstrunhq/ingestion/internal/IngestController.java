package com.firstrunhq.ingestion.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.apps.AppDirectory;
import com.firstrunhq.apps.SdkApp;
import com.firstrunhq.apps.SignatureVerifier;
import com.firstrunhq.ingestion.EventEnvelope;
import com.firstrunhq.ingestion.EventTopics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IngestController {

  // Endpoint contract from api/openapi/ingest.yaml, read here and mirrored by the CORS policy.
  static final String PATH = "/v1/e";
  static final String HEADER_SDK_KEY = "X-FirstRun-Key";
  static final String HEADER_TIMESTAMP = "X-FirstRun-Timestamp";
  static final String HEADER_SIGNATURE = "X-FirstRun-Signature";

  private static final int MAX_BODY_BYTES = 64 * 1024;
  private static final int MAX_BATCH_EVENTS = 50;

  private final AppDirectory appDirectory;
  private final SignatureVerifier signatureVerifier;
  private final EventDeduper deduper;
  private final TenantRateLimiter rateLimiter;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final IngestProperties properties;

  IngestController(
      AppDirectory appDirectory,
      SignatureVerifier signatureVerifier,
      EventDeduper deduper,
      TenantRateLimiter rateLimiter,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      Validator validator,
      IngestProperties properties) {
    this.appDirectory = appDirectory;
    this.signatureVerifier = signatureVerifier;
    this.deduper = deduper;
    this.rateLimiter = rateLimiter;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.properties = properties;
  }

  /**
   * Authenticates the batch, then dedupes, minimizes, and produces it to the event stream. The
   * authentication headers are optional so a missing one answers as its own check's failure (401),
   * not a framework 400.
   */
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Object> ingestEvents(
      @RequestHeader(value = HEADER_SDK_KEY, required = false) @Nullable String sdkKey,
      @RequestHeader(value = HEADER_TIMESTAMP, required = false) @Nullable String timestamp,
      @RequestHeader(value = HEADER_SIGNATURE, required = false) @Nullable String signature,
      @RequestHeader(value = HttpHeaders.ORIGIN, required = false) @Nullable String origin,
      HttpServletRequest request)
      throws IOException {
    // Reading at most one byte past the limit bounds the buffer, whatever Content-Length claims.
    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);

    SdkApp app = sdkKey == null ? null : appDirectory.findBySdkKey(sdkKey).orElse(null);
    if (app == null) {
      return problem(HttpStatus.UNAUTHORIZED, "No app owns this SDK key.");
    }

    // The origin gate runs before the signature check. The HMAC key ships in the widget bundle,
    // so the allowed-origins list, not the key, carries the off-site forgery defense.
    if (origin == null || !app.allowedOrigins().contains(origin)) {
      return problem(HttpStatus.FORBIDDEN, "The Origin is not on the app's allowed origins.");
    }
    if (body.length > MAX_BODY_BYTES) {
      return problem(HttpStatus.PAYLOAD_TOO_LARGE, "The body exceeds 64 KB.");
    }
    if (timestamp == null
        || signature == null
        || !signatureVerifier.verify(app.hmacKey(), timestamp, body, signature)) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "The signature is invalid or the timestamp is outside the accepted window.");
    }

    EventBatch batch;
    try {
      batch = objectMapper.readValue(body, EventBatch.class);
    } catch (IOException malformed) {
      return problem(HttpStatus.BAD_REQUEST, "The body is not a valid event batch.");
    }
    if (batch.events().size() > MAX_BATCH_EVENTS) {
      return problem(HttpStatus.PAYLOAD_TOO_LARGE, "More than 50 events in one batch.");
    }

    Set<ConstraintViolation<EventBatch>> violations = validator.validate(batch);
    if (!violations.isEmpty()) {
      ConstraintViolation<EventBatch> first = violations.iterator().next();
      return problem(HttpStatus.BAD_REQUEST, first.getPropertyPath() + " " + first.getMessage());
    }

    long retryAfter = rateLimiter.retryAfterSeconds(app.tenantId(), batch.events().size());
    if (retryAfter > 0) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter))
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              ProblemDetail.forStatusAndDetail(
                  HttpStatus.TOO_MANY_REQUESTS, "The tenant's event rate limit is exhausted."));
    }
    return produce(app, batch, IpTruncator.truncate(clientAddress(request)));
  }

  private ResponseEntity<Object> produce(SdkApp app, EventBatch batch, @Nullable String ip)
      throws JsonProcessingException {
    Instant receivedAt = Instant.now();

    // sent_at against the gateway clock measures the client's clock skew, and each event time
    // shifts by it (Segment's recipe for the same fields).
    Duration skew =
        batch.sentAt() == null ? Duration.ZERO : Duration.between(batch.sentAt(), receivedAt);

    List<UUID> claimed = new ArrayList<>();
    List<CompletableFuture<SendResult<String, String>>> deliveries = new ArrayList<>();
    for (Event event : batch.events()) {
      if (!deduper.firstDelivery(app.id(), event.id())) {
        continue;
      }
      claimed.add(event.id());

      // Correction still trusts the client's own event time, and nothing occurs after arrival,
      // so any residue past received_at is clock error and clamps to it.
      Instant corrected = event.timestamp().plus(skew);

      EventEnvelope envelope =
          new EventEnvelope(
              app.tenantId(),
              app.id(),
              receivedAt,
              ip,
              event.id(),
              event.event(),
              event.endUserHash(),
              event.sessionId(),
              event.ref(),
              corrected.isAfter(receivedAt) ? receivedAt : corrected,
              allowlisted(event.properties(), app.allowedProperties()));
      deliveries.add(
          kafkaTemplate.send(
              EventTopics.EVENTS_RAW,
              partitionKey(app.tenantId(), event.endUserHash()),
              objectMapper.writeValueAsString(envelope)));
    }

    // A 202 promises the batch reached the event stream, so wait for the broker's
    // acknowledgement and turn an outage into a retryable 503 instead of silent loss.
    // Releasing the claims lets that retry pass the dedupe.
    try {
      CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new))
          .get(properties.produceTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      deduper.release(app.id(), claimed);
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "The event stream is unavailable.");
    } catch (ExecutionException | TimeoutException unacknowledged) {
      deduper.release(app.id(), claimed);
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "The event stream is unavailable.");
    }
    return ResponseEntity.accepted().build();
  }

  /** Keeps only founder-allowlisted property keys, so unlisted ones never reach the stream. */
  private static @Nullable Map<String, Object> allowlisted(
      @Nullable Map<String, Object> properties, Set<String> allowed) {
    if (properties == null) {
      return null;
    }
    Map<String, Object> kept = new LinkedHashMap<>(properties);
    kept.keySet().retainAll(allowed);
    return kept;
  }

  /**
   * Hashes tenant_id and end_user_hash into the Kafka partition key, so one user's events stay
   * ordered within a partition.
   */
  private static String partitionKey(UUID tenantId, String endUserHash) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed =
          digest.digest((tenantId + ":" + endUserHash).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
    }
  }

  /**
   * Reads the client address from the last X-Forwarded-For entry, the one the balancer appended.
   * Earlier entries arrive client-controlled, so only the last is believed.
   */
  private static String clientAddress(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null) {
      return request.getRemoteAddr();
    }
    return forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
  }

  private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(status, detail));
  }
}
