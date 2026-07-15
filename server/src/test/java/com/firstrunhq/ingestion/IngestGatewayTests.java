package com.firstrunhq.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Exercises the ingest gateway end to end against throwaway Postgres, Redpanda, and Redis. Covers
 * the origin gate, signature window, dedupe, property scrub, IP truncation, clock-skew correction,
 * rate shedding, and the dead-letter route every listener inherits.
 */
@Tag("integration")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "firstrun.ingest.rate-capacity=100",
      "firstrun.ingest.rate-refill-per-second=1",
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@Import({TestcontainersConfiguration.class, IngestGatewayTests.DeadLetterProbe.class})
class IngestGatewayTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-0000000000a1";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000a2";
  private static final String KEY_A = "key_ingest_a";
  private static final String HMAC_A = "hmac_a_secret";
  private static final String ORIGIN_A = "https://app-a.example";

  private static final String TENANT_B = "019813f2-0000-7000-8000-0000000000b1";
  private static final String APP_B = "019813f2-0000-7000-8000-0000000000b2";
  private static final String KEY_B = "key_ingest_b";
  private static final String HMAC_B = "hmac_b_secret";
  private static final String ORIGIN_B = "https://app-b.example";

  private final TestRestTemplate rest;
  private final DataSource dataSource;
  private final ObjectMapper objectMapper;
  private final ConsumerFactory<String, String> consumerFactory;
  private final KafkaTemplate<String, String> kafkaTemplate;

  IngestGatewayTests(
      TestRestTemplate rest,
      DataSource dataSource,
      ObjectMapper objectMapper,
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate) {
    this.rest = rest;
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
    this.consumerFactory = consumerFactory;
    this.kafkaTemplate = kafkaTemplate;
  }

  @BeforeEach
  void seedAppsAndUseAnOriginCapableClient() throws SQLException {
    // The JDK HttpURLConnection silently drops Origin, so tests talk java.net.http.
    rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    try (var connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          INSERT INTO tenant (id, name) VALUES
            ('%s', 'Ingest Tenant A'), ('%s', 'Ingest Tenant B')
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(TENANT_A, TENANT_B));
      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key, allowed_origins, allowed_properties)
          VALUES
            ('%s', '%s', 'App A', '%s', '%s', '{%s}', '{plan}'),
            ('%s', '%s', 'App B', '%s', '%s', '{%s}', '{}')
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(
                  APP_A, TENANT_A, KEY_A, HMAC_A, ORIGIN_A, APP_B, TENANT_B, KEY_B, HMAC_B,
                  ORIGIN_B));
    }
  }

  @Test
  void acceptsASignedBatchAndProducesAScrubbedEnvelope() throws JsonProcessingException {
    String endUserHash = "user-" + UUID.randomUUID();
    String body = batch(1, endUserHash, Map.of("plan", "pro", "email", "pii@example.com"));
    HttpHeaders extra = new HttpHeaders();

    // The last entry is the balancer-appended client, the only one the gateway believes.
    extra.set("X-Forwarded-For", "6.6.6.6, 12.214.31.144");

    ResponseEntity<String> response = post(KEY_A, HMAC_A, ORIGIN_A, body, extra);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    ConsumerRecord<String, String> record =
        awaitRecord(EventTopics.EVENTS_RAW, value -> value.contains(endUserHash));
    assertThat(record.key()).isEqualTo(sha256Hex(TENANT_A + ":" + endUserHash));

    JsonNode envelope = objectMapper.readTree(record.value());
    assertThat(envelope.get("tenant_id").asText()).isEqualTo(TENANT_A);
    assertThat(envelope.get("app_id").asText()).isEqualTo(APP_A);
    assertThat(Instant.parse(envelope.get("received_at").asText())).isNotNull();
    assertThat(envelope.get("ip").asText()).isEqualTo("12.214.31.0");
    assertThat(envelope.get("properties").properties()).hasSize(1);
    assertThat(envelope.get("properties").get("plan").asText()).isEqualTo("pro");
  }

  @Test
  void correctsEventTimeByTheClientClockSkew() throws JsonProcessingException {
    String endUserHash = "user-" + UUID.randomUUID();

    // With the event time equal to sent_at, the corrected time must equal received_at exactly.
    Instant skewedClock = Instant.now().minus(Duration.ofMinutes(2));
    String body = batch(1, endUserHash, Map.of(), skewedClock);

    assertThat(post(KEY_A, HMAC_A, ORIGIN_A, body, null).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    ConsumerRecord<String, String> record =
        awaitRecord(EventTopics.EVENTS_RAW, value -> value.contains(endUserHash));

    JsonNode envelope = objectMapper.readTree(record.value());
    assertThat(envelope.get("timestamp").asText()).isEqualTo(envelope.get("received_at").asText());
  }

  @Test
  void boundsACorrectedEventTimeByArrival() throws JsonProcessingException {
    String endUserHash = "user-" + UUID.randomUUID();

    // An event time past sent_at survives skew correction, so the gateway clamps it to arrival.
    Instant sentAt = Instant.now();
    String body = batch(1, endUserHash, Map.of(), sentAt, sentAt.plus(Duration.ofHours(2)));

    assertThat(post(KEY_A, HMAC_A, ORIGIN_A, body, null).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    ConsumerRecord<String, String> record =
        awaitRecord(EventTopics.EVENTS_RAW, value -> value.contains(endUserHash));

    JsonNode envelope = objectMapper.readTree(record.value());
    assertThat(envelope.get("timestamp").asText()).isEqualTo(envelope.get("received_at").asText());
  }

  @Test
  void dropsARedeliveredEventUuid() throws JsonProcessingException {
    String endUserHash = "user-" + UUID.randomUUID();
    String body = batch(1, endUserHash, Map.of("plan", "solo"));

    assertThat(post(KEY_A, HMAC_A, ORIGIN_A, body, null).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(post(KEY_A, HMAC_A, ORIGIN_A, body, null).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    List<ConsumerRecord<String, String>> records =
        drain(EventTopics.EVENTS_RAW, Duration.ofSeconds(5)).stream()
            .filter(record -> record.value().contains(endUserHash))
            .toList();
    assertThat(records).hasSize(1);
  }

  @Test
  void rejectsABadSignatureWithAProblem() throws JsonProcessingException {
    String body = batch(1, "user-sig", Map.of());
    String timestamp = Instant.now().toString();

    ResponseEntity<String> response = post(KEY_A, ORIGIN_A, timestamp, "0".repeat(64), body, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void rejectsATimestampOutsideTheWindow() throws JsonProcessingException {
    String body = batch(1, "user-stale", Map.of());
    String stale = Instant.now().minus(Duration.ofMinutes(6)).toString();

    ResponseEntity<String> response =
        post(KEY_A, ORIGIN_A, stale, sign(HMAC_A, stale, body), body, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsAnUnknownSdkKey() throws JsonProcessingException {
    String body = batch(1, "user-unknown", Map.of());

    assertThat(post("key_nobody", HMAC_A, ORIGIN_A, body, null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsADisallowedOriginBeforeTheSignatureCheck() throws JsonProcessingException {
    String body = batch(1, "user-origin", Map.of());
    String timestamp = Instant.now().toString();

    ResponseEntity<String> offSite =
        post(KEY_A, "https://evil.example", timestamp, "0".repeat(64), body, null);
    assertThat(offSite.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<String> absent =
        post(KEY_A, null, timestamp, sign(HMAC_A, timestamp, body), body, null);
    assertThat(absent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void rejectsOversizedBatches() throws JsonProcessingException {
    String tooMany = batch(51, "user-many", Map.of());
    assertThat(post(KEY_A, HMAC_A, ORIGIN_A, tooMany, null).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

    String tooBig = batch(1, "user-big", Map.of("plan", "x".repeat(70_000)));
    assertThat(post(KEY_A, HMAC_A, ORIGIN_A, tooBig, null).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void rejectsABodyThatFailsTheSchema() {
    String timestamp = Instant.now().toString();
    String malformed = "{\"events\":";
    assertThat(
            post(KEY_A, ORIGIN_A, timestamp, sign(HMAC_A, timestamp, malformed), malformed, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    String empty = "{\"events\":[]}";
    assertThat(
            post(KEY_A, ORIGIN_A, timestamp, sign(HMAC_A, timestamp, empty), empty, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void shedsLoadPerTenantWithRetryAfter() throws JsonProcessingException {
    assertThat(
            post(KEY_B, HMAC_B, ORIGIN_B, batch(50, "user-flood", Map.of()), null).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            post(KEY_B, HMAC_B, ORIGIN_B, batch(50, "user-flood", Map.of()), null).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    ResponseEntity<String> shed =
        post(KEY_B, HMAC_B, ORIGIN_B, batch(50, "user-flood", Map.of()), null);
    assertThat(shed.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    String retryAfter = shed.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    assertThat(retryAfter).isNotNull();
    assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void deadLettersAPoisonRecordToTheDlqSuffix() {
    kafkaTemplate.send(DeadLetterProbe.TOPIC, "poison-key", "poison-value");

    ConsumerRecord<String, String> dead =
        awaitRecord(
            DeadLetterProbe.TOPIC + EventTopics.DLQ_SUFFIX, value -> value.equals("poison-value"));
    assertThat(dead.key()).isEqualTo("poison-key");
  }

  /** A listener that always fails, so the shared error handler's dead-letter route is provable. */
  @TestConfiguration(proxyBeanMethods = false)
  static class DeadLetterProbe {

    static final String TOPIC = "ingest.dlqproof";

    @Bean
    NewTopic dlqProofTopic() {
      return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic dlqProofDlqTopic() {
      return TopicBuilder.name(TOPIC + EventTopics.DLQ_SUFFIX).partitions(1).replicas(1).build();
    }

    @KafkaListener(topics = TOPIC, groupId = "dlq-proof")
    void alwaysPoison(String value) {
      throw new IllegalStateException("poison: " + value);
    }
  }

  private String batch(int events, String endUserHash, Map<String, Object> properties)
      throws JsonProcessingException {
    return batch(events, endUserHash, properties, Instant.now());
  }

  /** Builds a batch whose {@code sent_at} and event timestamps all read {@code clientClock}. */
  private String batch(
      int events, String endUserHash, Map<String, Object> properties, Instant clientClock)
      throws JsonProcessingException {
    return batch(events, endUserHash, properties, clientClock, clientClock);
  }

  private String batch(
      int events, String endUserHash, Map<String, Object> properties, Instant sentAt, Instant at)
      throws JsonProcessingException {
    List<Map<String, Object>> list = new ArrayList<>();
    for (int i = 0; i < events; i++) {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("id", UUID.randomUUID().toString());
      event.put("event", "task_created");
      event.put("end_user_hash", endUserHash);
      event.put("timestamp", at.toString());
      event.put("properties", properties);
      list.add(event);
    }
    return objectMapper.writeValueAsString(Map.of("sent_at", sentAt.toString(), "events", list));
  }

  private ResponseEntity<String> post(
      String sdkKey, String hmacKey, String origin, String body, @Nullable HttpHeaders extra) {
    String timestamp = Instant.now().toString();
    return post(sdkKey, origin, timestamp, sign(hmacKey, timestamp, body), body, extra);
  }

  private ResponseEntity<String> post(
      String sdkKey,
      @Nullable String origin,
      String timestamp,
      String signature,
      String body,
      @Nullable HttpHeaders extra) {
    HttpHeaders headers = new HttpHeaders();
    if (extra != null) {
      headers.addAll(extra);
    }
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-FirstRun-Key", sdkKey);
    headers.set("X-FirstRun-Timestamp", timestamp);
    headers.set("X-FirstRun-Signature", signature);
    if (origin != null) {
      headers.set(HttpHeaders.ORIGIN, origin);
    }
    return rest.exchange("/v1/e", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private List<ConsumerRecord<String, String>> drain(String topic, Duration window) {
    List<ConsumerRecord<String, String>> records = new ArrayList<>();
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("drain-" + UUID.randomUUID(), null)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + window.toNanos();
      while (System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(250)).forEach(records::add);
      }
    }
    return records;
  }

  private ConsumerRecord<String, String> awaitRecord(String topic, Predicate<String> match) {
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("await-" + UUID.randomUUID(), null)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
          if (match.test(record.value())) {
            return record;
          }
        }
      }
    }
    throw new AssertionError("no matching record arrived on " + topic);
  }

  private static String sign(String hmacKey, String timestamp, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(body.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
