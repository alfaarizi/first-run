package com.firstrunhq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.ingestion.EventEnvelope;
import com.firstrunhq.ingestion.EventTopics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Drives the stream processor end to end through throwaway Redpanda, Postgres, and Redis: state
 * machine advance, event-UUID dedupe, session features with their idle expiry, and the poison route
 * to the dead-letter queue.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FunnelStateMachineTests {

  private static final String TENANT = "019813f2-0000-7000-8000-0000000000e1";
  private static final String APP = "019813f2-0000-7000-8000-0000000000e2";
  private static final String MILESTONE_ONE = "task_created";
  private static final String MILESTONE_TWO = "report_shared";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ConsumerFactory<String, String> consumerFactory;
  private final ObjectMapper objectMapper;
  private final DataSource dataSource;
  private final StringRedisTemplate redis;

  FunnelStateMachineTests(
      KafkaTemplate<String, String> kafkaTemplate,
      ConsumerFactory<String, String> consumerFactory,
      ObjectMapper objectMapper,
      DataSource dataSource,
      StringRedisTemplate redis) {
    this.kafkaTemplate = kafkaTemplate;
    this.consumerFactory = consumerFactory;
    this.objectMapper = objectMapper;
    this.dataSource = dataSource;
    this.redis = redis;
  }

  @BeforeEach
  void seedTenantAppAndMilestones() throws SQLException {
    // The container's default user is a superuser, so these inserts bypass RLS.
    try (var connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO tenant (id, name) VALUES ('%s', 'Funnel Tenant') ON CONFLICT (id) DO NOTHING"
              .formatted(TENANT));
      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES ('%s', '%s', 'Funnel App', 'key_funnel', 'hmac_funnel')
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(APP, TENANT));
      statement.execute(
          """
          INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
          VALUES
            ('019813f2-0000-7000-8000-0000000000e3', '%s', '%s', '%s', 'Create a task', 1),
            ('019813f2-0000-7000-8000-0000000000e4', '%s', '%s', '%s', 'Share a report', 2)
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(TENANT, APP, MILESTONE_ONE, TENANT, APP, MILESTONE_TWO));
    }
  }

  @Test
  void advancesAUserThroughTheStateMachine() throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();

    send(event(user, "fr.page_view").inSession(session).withProperties(Map.of("path", "/home")));
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
    assertThat(state(user, MILESTONE_TWO)).isNull();

    send(event(user, MILESTONE_ONE).inSession(session));
    awaitState(user, MILESTONE_ONE, "COMPLETED");
    assertThat(state(user, MILESTONE_TWO)).isNull();

    send(event(user, "fr.click").inSession(session));
    awaitState(user, MILESTONE_TWO, "IN_PROGRESS");
    assertThat(state(user, MILESTONE_ONE)).isEqualTo("COMPLETED");

    send(event(user, MILESTONE_TWO).inSession(session));
    awaitState(user, MILESTONE_TWO, "COMPLETED");
    assertThat(endUsers(user)).isEqualTo(1);
  }

  @Test
  void completesAMilestoneOutOfOrder() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();

    // The completion event touches only its milestone, so the skipped step stays PENDING.
    send(event(user, MILESTONE_TWO));
    awaitState(user, MILESTONE_TWO, "COMPLETED");
    assertThat(state(user, MILESTONE_ONE)).isNull();
  }

  @Test
  void clampsCompletionTimeToTheStartTime() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant startedAt = Instant.now();
    send(event(user, "fr.click").at(startedAt));
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
    // The completion carries an earlier client time than the event that opened the step.
    send(event(user, MILESTONE_ONE).at(startedAt.minusSeconds(300)));
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // completed_at never precedes started_at, so time-to-complete never reads negative.
    assertThat(completedAtEqualsStartedAt(user, MILESTONE_ONE)).isTrue();
  }

  @Test
  void skipsARedeliveredEventUuid() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    EventBuilder error = event(user, "fr.error").inSession(session);

    send(error);
    send(error);
    // The marker shares the user's partition key, so its arrival orders after both copies.
    send(event(user, "fr.click").inSession(session));

    String key = sessionKey(session);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "last_event"))
                    .isEqualTo("fr.click"));
    assertThat(redis.<String, String>opsForHash().get(key, "errors")).isEqualTo("1");
  }

  @Test
  void completionAppliesEvenWhenItsIdIsAlreadyClaimed() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID id = UUID.randomUUID();
    // A completion applies even when its id is already claimed, so a crash between claim and commit
    // cannot strand the funnel. The click claims the id, then the completion reuses it.
    send(event(user, "fr.click").withId(id));
    send(event(user, MILESTONE_ONE).withId(id));
    awaitState(user, MILESTONE_ONE, "COMPLETED");
  }

  @Test
  void aReplayedNonMilestoneEventDoesNotAdvanceTheFunnel() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant clickAt = Instant.now();
    EventBuilder click = event(user, "fr.click").at(clickAt);
    send(click); // opens step one and claims the id
    send(event(user, MILESTONE_ONE).at(clickAt.plusSeconds(1)));
    send(click); // a duplicate now, must not open step two
    send(event(user, MILESTONE_TWO).at(clickAt.plusSeconds(60)));
    awaitState(user, MILESTONE_TWO, "COMPLETED");

    // Step two only ever completed. Had the duplicate opened it, started_at would predate that.
    assertThat(completedAtEqualsStartedAt(user, MILESTONE_TWO)).isTrue();
  }

  @Test
  void recordsSessionFeaturesWithAnIdleExpiry() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();

    send(event(user, "fr.page_view").inSession(session).withProperties(Map.of("path", "/a")));
    send(event(user, "fr.page_view").inSession(session).withProperties(Map.of("path", "/b")));
    send(event(user, "fr.page_view").inSession(session).withProperties(Map.of("path", "/a")));
    send(event(user, "fr.error").inSession(session));
    send(event(user, "fr.error").inSession(session));

    String key = sessionKey(session);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(redis.<String, String>opsForHash().get(key, "errors")).isEqualTo("2"));

    Map<String, String> features = redis.<String, String>opsForHash().entries(key);
    assertThat(features.get("backtracks")).isEqualTo("1");
    assertThat(features.get("retries")).isEqualTo("1");
    assertThat(features.get("step_position")).isEqualTo("1");
    assertThat(features.get("dwell_seconds")).isNotNull();
    assertThat(features.get("started_at")).isNotNull();
    // The idle window closes the session by expiry, so the key never outlives 30 minutes.
    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
    assertThat(ttl)
        .isNotNull()
        .isPositive()
        .isLessThanOrEqualTo(Duration.ofMinutes(30).toSeconds());
  }

  @Test
  void deadLettersAPoisonEnvelope() {
    String poison = "poison-" + UUID.randomUUID();
    kafkaTemplate.send(EventTopics.EVENTS_RAW, poison, "not an envelope");

    ConsumerRecord<String, String> dead =
        awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> poison.equals(record.key()));
    assertThat(dead.value()).isEqualTo("not an envelope");
  }

  @Test
  void deadLettersAnEnvelopeMissingARequiredField() throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();
    // A structurally valid envelope with no event: Jackson accepts it, the processor must not.
    String value =
        objectMapper.writeValueAsString(
            Map.of(
                "tenant_id",
                TENANT,
                "app_id",
                APP,
                "received_at",
                Instant.now().toString(),
                "id",
                UUID.randomUUID().toString(),
                "end_user_hash",
                user,
                "timestamp",
                Instant.now().toString()));
    kafkaTemplate.send(EventTopics.EVENTS_RAW, user, value);

    ConsumerRecord<String, String> dead =
        awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> user.equals(record.key()));
    assertThat(dead.value()).isEqualTo(value);
    // Rejected before any write, so the end user was never created.
    assertThat(endUsers(user)).isZero();
  }

  private static EventBuilder event(String endUserHash, String name) {
    return new EventBuilder(endUserHash, name);
  }

  /** Builds one events.raw envelope, defaulting every field a test does not name. */
  private static final class EventBuilder {
    private final String endUserHash;
    private final String event;
    private UUID id = UUID.randomUUID();
    private @Nullable UUID sessionId;
    private Instant at = Instant.now();
    private @Nullable Map<String, Object> properties;

    private EventBuilder(String endUserHash, String event) {
      this.endUserHash = endUserHash;
      this.event = event;
    }

    private EventBuilder withId(UUID id) {
      this.id = id;
      return this;
    }

    private EventBuilder inSession(UUID sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    private EventBuilder at(Instant at) {
      this.at = at;
      return this;
    }

    private EventBuilder withProperties(Map<String, Object> properties) {
      this.properties = properties;
      return this;
    }

    private EventEnvelope build() {
      return new EventEnvelope(
          UUID.fromString(TENANT),
          UUID.fromString(APP),
          at,
          null,
          id,
          event,
          endUserHash,
          sessionId,
          at,
          properties);
    }
  }

  /** Keys by the user hash so one user's events stay ordered, like the gateway's partition key. */
  private void send(EventBuilder event) throws JsonProcessingException {
    EventEnvelope envelope = event.build();
    kafkaTemplate.send(
        EventTopics.EVENTS_RAW, envelope.endUserHash(), objectMapper.writeValueAsString(envelope));
  }

  // Where SessionFeatureStore keeps one session's stuck-signal features.
  private static String sessionKey(UUID session) {
    return "session:%s:%s".formatted(APP, session);
  }

  private void awaitState(String endUserHash, String milestone, String expected) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(state(endUserHash, milestone)).isEqualTo(expected));
  }

  private @Nullable String state(String endUserHash, String milestone) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT p.state
                FROM milestone_progress p
                JOIN end_user u ON u.id = p.end_user_id
                JOIN milestone m ON m.id = p.milestone_id
                WHERE u.external_hash = ? AND m.name = ?
                """)) {
      statement.setString(1, endUserHash);
      statement.setString(2, milestone);
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? row.getString(1) : null;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private boolean completedAtEqualsStartedAt(String endUserHash, String milestone) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT p.completed_at = p.started_at
                FROM milestone_progress p
                JOIN end_user u ON u.id = p.end_user_id
                JOIN milestone m ON m.id = p.milestone_id
                WHERE u.external_hash = ? AND m.name = ?
                """)) {
      statement.setString(1, endUserHash);
      statement.setString(2, milestone);
      try (ResultSet row = statement.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private int endUsers(String endUserHash) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("SELECT count(*) FROM end_user WHERE external_hash = ?")) {
      statement.setString(1, endUserHash);
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  private ConsumerRecord<String, String> awaitRecord(
      String topic, java.util.function.Predicate<ConsumerRecord<String, String>> match) {
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("await-" + UUID.randomUUID(), null)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
          if (match.test(record)) {
            return record;
          }
        }
      }
    }
    throw new AssertionError("no matching record arrived on " + topic);
  }
}
