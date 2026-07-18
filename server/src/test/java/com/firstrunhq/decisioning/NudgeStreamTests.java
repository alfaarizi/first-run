package com.firstrunhq.decisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.IntegrationTest;
import com.firstrunhq.funnel.CandidateEnvelope;
import com.firstrunhq.funnel.CandidateTopics;
import com.firstrunhq.testfixture.TestSeeder;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Exercises the widget stream end to end: the signature gate on {@code /v1/stream}, a candidate on
 * {@code intervention.candidates} arriving as one {@code nudge} frame on the connected user's
 * stream, replay on reconnect, the holdout drop, and the per-user stream cap. End user hashes are
 * fixed strings verified to bucket outside this tenant's holdout, except where the test wants the
 * opposite, so the nudge path opens or closes deterministically.
 */
@IntegrationTest
class NudgeStreamTests {

  private static final String TENANT = "019813f2-0000-7000-8000-000000000401";
  private static final String APP = "019813f2-0000-7000-8000-000000000402";
  private static final String MILESTONE = "019813f2-0000-7000-8000-000000000403";
  private static final String SDK_KEY = "key_" + APP;
  private static final String HMAC_KEY = "hmac_" + APP;

  private final TestRestTemplate rest;
  private final DataSource dataSource;
  private final ObjectMapper objectMapper;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final List<HttpClient> streamClients = new ArrayList<>();

  NudgeStreamTests(
      TestRestTemplate rest,
      DataSource dataSource,
      ObjectMapper objectMapper,
      KafkaTemplate<String, String> kafkaTemplate) {
    this.rest = rest;
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
    this.kafkaTemplate = kafkaTemplate;
  }

  @BeforeEach
  void seedAndUseAnOriginCapableClient() throws SQLException {
    // The JDK HttpURLConnection silently drops Origin, so tests talk java.net.http.
    rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    TestSeeder.tenant(dataSource, TENANT, "Stream Tenant");
    TestSeeder.app(dataSource, APP, TENANT, "Stream App");
    TestSeeder.milestone(
        dataSource, MILESTONE, TENANT, APP, "task_created", "Create your first task", 1);
  }

  @AfterEach
  void closeStreamClients() {
    // an open SSE connection holds Tomcat's graceful shutdown for the stream's full timeout
    streamClients.forEach(HttpClient::shutdownNow);
  }

  @Test
  void pushesTheCandidateAsANudgeOnTheUsersStream() throws Exception {
    String endUserHash = "user-push";
    BlockingQueue<String> lines = openStream(endUserHash);

    CandidateEnvelope candidate = candidate(endUserHash, UUID.randomUUID());
    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(candidate));

    assertThat(awaitLine(lines, line -> line.startsWith("event:") && line.contains("nudge")))
        .isNotNull();
    String data = awaitLine(lines, line -> line.startsWith("data:"));
    assertThat(data).isNotNull();

    JsonNode frame = objectMapper.readTree(data.substring("data:".length()).trim());
    assertThat(frame.get("id").asText()).isEqualTo(candidate.id().toString());
    assertThat(frame.get("text").asText()).contains("Create your first task");
  }

  @Test
  void deliversOneNudgePerFlaggingEvent() throws Exception {
    String endUserHash = "user-dedupe";
    BlockingQueue<String> lines = openStream(endUserHash);

    // A redelivered flagging event emits a candidate copy under a fresh id, same event_id.
    UUID flaggingEventId = UUID.randomUUID();
    for (int copy = 0; copy < 2; copy++) {
      kafkaTemplate.send(
          CandidateTopics.INTERVENTION_CANDIDATES,
          endUserHash,
          objectMapper.writeValueAsString(candidate(endUserHash, flaggingEventId)));
    }

    assertThat(awaitLine(lines, line -> line.startsWith("event:") && line.contains("nudge")))
        .isNotNull();
    assertThat(
            awaitLine(
                lines,
                line -> line.startsWith("event:") && line.contains("nudge"),
                Duration.ofSeconds(5)))
        .isNull();
  }

  @Test
  void buffersAnUndeliveredCandidateAndReplaysItOnReconnect() throws Exception {
    String endUserHash = "user-buffered";
    UUID flaggingEventId = UUID.randomUUID();
    CandidateEnvelope candidate = candidate(endUserHash, flaggingEventId);

    // The push reaches nobody, because the user has no open stream yet.
    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(candidate));

    // Give the consumer time to buffer the undeliverable candidate before the stream opens.
    Thread.sleep(2000);

    // The reserved cursor replays the whole buffer for a reconnect that never saw a frame id.
    BlockingQueue<String> lines = openStream(endUserHash, "earliest", null);
    String data = awaitLine(lines, line -> line.startsWith("data:"));
    assertThat(data).isNotNull().contains(candidate.id().toString());

    // The claim landed at buffering, so a candidate copy never duplicates the nudge.
    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(candidate(endUserHash, flaggingEventId)));
    assertThat(awaitLine(lines, line -> line.startsWith("data:"), Duration.ofSeconds(5))).isNull();
  }

  @Test
  void replaysOnlyTheFramesAfterTheLastEventId() throws Exception {
    String endUserHash = "user-replay-cursor";
    BlockingQueue<String> lines = openStream(endUserHash);

    CandidateEnvelope first = candidate(endUserHash, UUID.randomUUID());
    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(first));
    assertThat(awaitLine(lines, line -> line.contains(first.id().toString()))).isNotNull();

    CandidateEnvelope second = candidate(endUserHash, UUID.randomUUID());
    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(second));
    assertThat(awaitLine(lines, line -> line.contains(second.id().toString()))).isNotNull();

    // A reconnect that saw the first frame replays only the second.
    BlockingQueue<String> reconnect = openStream(endUserHash, first.id().toString(), null);
    String data = awaitLine(reconnect, line -> line.startsWith("data:"));
    assertThat(data).isNotNull().contains(second.id().toString());
    assertThat(awaitLine(reconnect, line -> line.startsWith("data:"), Duration.ofSeconds(2)))
        .isNull();
  }

  @Test
  void honorsTheLastEventIdHeaderOverTheQueryParameter() throws Exception {
    String endUserHash = "user-precedence";
    BlockingQueue<String> lines = openStream(endUserHash);

    CandidateEnvelope candidate = candidate(endUserHash, UUID.randomUUID());
    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(candidate));
    assertThat(awaitLine(lines, line -> line.contains(candidate.id().toString()))).isNotNull();

    // The header cursor already saw the frame, so it must silence the query's replay-all.
    BlockingQueue<String> reconnect =
        openStream(endUserHash, "earliest", candidate.id().toString());
    assertThat(awaitLine(reconnect, line -> line.startsWith("data:"), Duration.ofSeconds(3)))
        .isNull();
  }

  @Test
  void neverNudgesAHoldoutUser() throws Exception {
    // This hash buckets into the tenant's holdout under the deterministic assignment.
    String endUserHash = "user-control-2";
    BlockingQueue<String> lines = openStream(endUserHash);

    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(candidate(endUserHash, UUID.randomUUID())));

    assertThat(
            awaitLine(
                lines,
                line -> line.startsWith("event:") && line.contains("nudge"),
                Duration.ofSeconds(5)))
        .isNull();
  }

  @Test
  void retiresTheOldestStreamWhenAUserPassesTheCap() throws Exception {
    String endUserHash = "user-cap";
    BlockingQueue<String> oldest = openStream(endUserHash);

    // the ninth stream passes the per-user cap in NudgeStreams, retiring the first
    BlockingQueue<String> newest = oldest;
    for (int extra = 0; extra < 8; extra++) {
      newest = openStream(endUserHash);
    }

    // The retired frame tells the evicted widget to close instead of reconnecting,
    // which would evict the next stream and churn forever.
    assertThat(awaitLine(oldest, line -> line.startsWith("event:") && line.contains("retired")))
        .isNotNull();

    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(candidate(endUserHash, UUID.randomUUID())));

    assertThat(awaitLine(newest, line -> line.startsWith("event:") && line.contains("nudge")))
        .isNotNull();
    assertThat(
            awaitLine(
                oldest,
                line -> line.startsWith("event:") && line.contains("nudge"),
                Duration.ofSeconds(2)))
        .isNull();
  }

  @Test
  void readsAMalformedCursorAsAbsent() throws Exception {
    String endUserHash = "user-malformed";

    // Buffer a nudge with no stream open, so a resuming cursor would have something to replay.
    kafkaTemplate.send(
        CandidateTopics.INTERVENTION_CANDIDATES,
        endUserHash,
        objectMapper.writeValueAsString(candidate(endUserHash, UUID.randomUUID())));
    Thread.sleep(2000);

    // A cursor outside the frame-id alphabet names no position, so it reads as absent: live only.
    BlockingQueue<String> malformed = openStream(endUserHash, "Not:A/Cursor", null);
    assertThat(awaitLine(malformed, line -> line.startsWith("data:"), Duration.ofSeconds(3)))
        .isNull();

    // The reserved cursor proves the buffer still held the nudge the malformed open skipped.
    BlockingQueue<String> earliest = openStream(endUserHash, "earliest", null);
    assertThat(awaitLine(earliest, line -> line.startsWith("data:"))).isNotNull();
  }

  @Test
  void rejectsABadSignature() {
    String url = streamUrl(SDK_KEY, "user-sig", Instant.now().toString(), "0".repeat(64));
    assertThat(get(url, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsAnUnknownSdkKey() {
    String timestamp = Instant.now().toString();
    String url = streamUrl("key_nobody", "user-key", timestamp, sign(timestamp, "user-key"));
    assertThat(get(url, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsATimestampOutsideTheWindow() {
    String stale = Instant.now().minus(Duration.ofMinutes(6)).toString();
    String url = streamUrl(SDK_KEY, "user-stale", stale, sign(stale, "user-stale"));
    assertThat(get(url, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsADisallowedOriginWhenTheBrowserSendsOne() {
    String timestamp = Instant.now().toString();
    String url = streamUrl(SDK_KEY, "user-origin", timestamp, sign(timestamp, "user-origin"));
    assertThat(get(url, "https://evil.example").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private BlockingQueue<String> openStream(String endUserHash) throws Exception {
    return openStream(endUserHash, null, null);
  }

  /** Opens the signed stream, optionally with a replay cursor, and returns its lines. */
  private BlockingQueue<String> openStream(
      String endUserHash, @Nullable String cursorParam, @Nullable String cursorHeader)
      throws Exception {
    String timestamp = Instant.now().toString();
    String url = streamUrl(SDK_KEY, endUserHash, timestamp, sign(timestamp, endUserHash));

    if (cursorParam != null) {
      url += "&last_event_id=" + encode(cursorParam);
    }

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url)).header(HttpHeaders.ACCEPT, "text/event-stream");

    if (cursorHeader != null) {
      builder.header("Last-Event-ID", cursorHeader);
    }

    HttpRequest request = builder.build();
    HttpClient client = HttpClient.newHttpClient();
    streamClients.add(client);
    HttpResponse<Stream<String>> response =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).get(30, TimeUnit.SECONDS);
    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              try {
                response.body().forEach(lines::add);
              } catch (RuntimeException closed) {
                // the server closed the stream, the queue holds what arrived
              }
            });
    reader.setDaemon(true);
    reader.start();
    return lines;
  }

  private CandidateEnvelope candidate(String endUserHash, UUID flaggingEventId) {
    return new CandidateEnvelope(
        UUID.randomUUID(),
        UUID.fromString(TENANT),
        UUID.fromString(APP),
        endUserHash,
        null,
        flaggingEventId,
        UUID.fromString(MILESTONE),
        "task_created",
        CandidateEnvelope.Rule.ERRORS,
        new CandidateEnvelope.SessionFeatures(120, 0, 0, 3, "/"),
        Instant.now());
  }

  private String streamUrl(String key, String endUserHash, String timestamp, String signature) {
    return rest.getRootUri()
        + "/v1/stream?key=%s&end_user_hash=%s&ts=%s&sig=%s"
            .formatted(encode(key), encode(endUserHash), encode(timestamp), encode(signature));
  }

  private ResponseEntity<String> get(String url, @Nullable String origin) {
    HttpHeaders headers = new HttpHeaders();
    if (origin != null) {
      headers.set(HttpHeaders.ORIGIN, origin);
    }
    return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private static @Nullable String awaitLine(BlockingQueue<String> lines, Predicate<String> match) {
    return awaitLine(lines, match, Duration.ofSeconds(30));
  }

  private static @Nullable String awaitLine(
      BlockingQueue<String> lines, Predicate<String> match, Duration window) {
    long deadline = System.nanoTime() + window.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        String line = lines.poll(250, TimeUnit.MILLISECONDS);
        if (line != null && match.test(line)) {
          return line;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  private static String sign(String timestamp, String endUserHash) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(endUserHash.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
