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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
  void anEventOlderThanTheMilestoneDoesNotCompleteIt() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    // A completion-named event that predates the milestone reads as any other activity, so a
    // replay against a grown catalog never completes milestones retroactively.
    send(event(user, MILESTONE_TWO).at(Instant.now().minus(Duration.ofHours(1))));
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
    assertThat(state(user, MILESTONE_TWO)).isNull();
  }

  @Test
  void clampsCompletionTimeToTheStartTime() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    // Times sit in the near future so the out-of-order completion still postdates the milestone.
    Instant startedAt = Instant.now().plusSeconds(600);
    send(event(user, "fr.click").at(startedAt));
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
    // The completion carries an earlier client time than the event that opened the step.
    send(event(user, MILESTONE_ONE).at(startedAt.minusSeconds(300)));
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // completed_at never precedes started_at, so time-to-complete never reads negative.
    assertThat(progressTime(user, MILESTONE_ONE, "completed_at"))
        .isEqualTo(progressTime(user, MILESTONE_ONE, "started_at"));
  }

  @Test
  void keepsTheEarliestCompletionTime() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    // Future times so every out-of-order copy still postdates the milestone's creation.
    Instant openedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    send(event(user, "fr.click").at(openedAt));
    send(event(user, MILESTONE_ONE).at(openedAt.plusSeconds(120)));
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // The user fired the completion event twice and the earlier copy arrived second.
    send(event(user, MILESTONE_ONE).at(openedAt.plusSeconds(60)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(progressTime(user, MILESTONE_ONE, "completed_at"))
                    .isEqualTo(openedAt.plusSeconds(60)));
  }

  @Test
  void keepsTheEarliestEntryToTheCurrentStep() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant enteredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    send(event(user, "fr.click").at(enteredAt.plusSeconds(60)));
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");

    // The user's earlier activity on the same step arrives late and marks the true entry.
    send(event(user, "fr.click").at(enteredAt));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(progressTime(user, MILESTONE_ONE, "started_at")).isEqualTo(enteredAt));
  }

  @Test
  void backdatesACompletedStepForLateActivityInsideIt() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    send(event(user, MILESTONE_ONE).at(completedAt));
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // Activity from before the completion arrives late: the entry moves, the completion stays.
    send(event(user, "fr.click").at(completedAt.minusSeconds(90)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(progressTime(user, MILESTONE_ONE, "started_at"))
                    .isEqualTo(completedAt.minusSeconds(90)));
    assertThat(progressTime(user, MILESTONE_ONE, "completed_at")).isEqualTo(completedAt);
    assertThat(state(user, MILESTONE_TWO)).isNull();
  }

  @Test
  void ignoresActivityFromBeforeACompletion() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    send(event(user, MILESTONE_ONE).at(completedAt));
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // A distinct late event from before the completion is activity on the finished step.
    send(event(user, "fr.click").at(completedAt.minusSeconds(60)));
    send(event(user, "fr.click").at(completedAt.plusSeconds(60)));
    awaitState(user, MILESTONE_TWO, "IN_PROGRESS");

    // Step two opened with the fresh event, not the stale one that arrived first.
    assertThat(progressTime(user, MILESTONE_TWO, "started_at"))
        .isEqualTo(completedAt.plusSeconds(60));
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
  void aClaimedDuplicateDoesNotCompleteAMilestoneDefinedLater()
      throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();
    EventBuilder invite = event(user, "invite_sent");
    send(invite);
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");

    // The founder names a milestone after the event flowed, then the same record redelivers.
    seedMilestone("invite_sent", 3);
    send(invite);
    // The marker shares the user's partition key, so its arrival orders after the duplicate.
    send(event(user, MILESTONE_ONE));
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    assertThat(state(user, "invite_sent")).isNull();
  }

  @Test
  void aReplayedNonMilestoneEventDoesNotAdvanceTheFunnel() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant clickAt = Instant.now();
    EventBuilder click = event(user, "fr.click").at(clickAt);

    // opens step one and claims the id
    send(click);
    send(event(user, MILESTONE_ONE).at(clickAt.plusSeconds(1)));

    // a duplicate now, must not open step two
    send(click);
    send(event(user, MILESTONE_TWO).at(clickAt.plusSeconds(60)));
    awaitState(user, MILESTONE_TWO, "COMPLETED");

    // Step two only ever completed. Had the duplicate opened it, started_at would predate that.
    assertThat(progressTime(user, MILESTONE_TWO, "completed_at"))
        .isEqualTo(progressTime(user, MILESTONE_TWO, "started_at"));
  }

  @Test
  void doesNotDoubleCountWhenAClaimIsLost() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    EventBuilder error = event(user, "fr.error").inSession(session);
    String key = sessionKey(session);

    send(error);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(redis.<String, String>opsForHash().get(key, "errors")).isEqualTo("1"));

    // A crash after the apply but before the claim: the claim is gone and the record redelivers.
    redis.delete(claimKey(error.id));
    send(error);
    send(event(user, "fr.click").inSession(session));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "last_event"))
                    .isEqualTo("fr.click"));
    // The session hash keeps the applied event id, so the replay counts nothing twice.
    assertThat(redis.<String, String>opsForHash().get(key, "errors")).isEqualTo("1");
    assertThat(redis.<String, String>opsForHash().get(key, "retries")).isNull();
  }

  @Test
  void restoresTheIdleExpiryOnRedelivery() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    EventBuilder click = event(user, "fr.click").inSession(session);
    String key = sessionKey(session);

    send(click);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isNotNull().isPositive());

    // A crash after the feature write can lose both the expiry and the claim. The redelivery is
    // a no-op for the counters but must restore the expiry, or the session outlives its window.
    redis.persist(key);
    redis.delete(claimKey(click.id));

    send(click);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.getExpire(key, TimeUnit.SECONDS))
                    .isNotNull()
                    .isPositive()
                    .isLessThanOrEqualTo(Duration.ofMinutes(30).toSeconds()));
  }

  @Test
  void aDeadLetteredEventStaysUnclaimedForReplay() throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();
    UUID lateTenant = UUID.randomUUID();
    UUID lateApp = UUID.randomUUID();
    // The tenant does not exist yet, so every retry fails and the record dead-letters.
    send(event(user, "fr.click").inApp(lateTenant, lateApp));
    ConsumerRecord<String, String> dead =
        awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> user.equals(record.key()));

    seedLateTenant(lateTenant, lateApp);
    // The failed apply never claimed the event id, so the replay opens the funnel normally.
    kafkaTemplate.send(EventTopics.EVENTS_RAW, user, dead.value());
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
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
  void skipsPageLogicForAMalformedPath() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    send(event(user, "fr.page_view").inSession(session).withProperties(Map.of("path", "/a")));

    // A path that is not a string is dropped whole, so it cannot corrupt counters or throw.
    EventBuilder malformed =
        event(user, "fr.page_view").inSession(session).withProperties(Map.of("path", 42));
    send(malformed);

    String key = sessionKey(session);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "last_event_id"))
                    .isEqualTo(malformed.id.toString()));
    Map<String, String> features = redis.<String, String>opsForHash().entries(key);
    assertThat(features.get("last_path")).isEqualTo("/a");
    assertThat(features).doesNotContainKey("backtracks");
  }

  @Test
  void neverShrinksDwellForAnOutOfOrderEvent() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant openedAt = Instant.now();

    send(event(user, "fr.page_view").inSession(session).at(openedAt));
    send(event(user, "fr.click").inSession(session).at(openedAt.plusSeconds(120)));
    // A late event carries an older client time and must not shrink the recorded dwell.
    send(event(user, "fr.click").inSession(session).at(openedAt.plusSeconds(30)));

    String key = sessionKey(session);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "last_event_at"))
                    .isEqualTo(openedAt.plusSeconds(30).toString()));
    assertThat(redis.<String, String>opsForHash().get(key, "dwell_seconds")).isEqualTo("120");
  }

  @Test
  void anchorsANewStepAtTheSessionsNewestActivity() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant openedAt = Instant.now().plusSeconds(600);

    send(event(user, "fr.click").inSession(session).at(openedAt));
    // A late completion opens step two while carrying a time before the click above.
    send(event(user, MILESTONE_ONE).inSession(session).at(openedAt.minusSeconds(120)));
    send(event(user, "fr.click").inSession(session).at(openedAt.plusSeconds(30)));

    String key = sessionKey(session);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "last_event_at"))
                    .isEqualTo(openedAt.plusSeconds(30).toString()));
    // Dwell on step two runs from the click that anchored it, not from the stale completion time.
    assertThat(redis.<String, String>opsForHash().get(key, "step_position")).isEqualTo("2");
    assertThat(redis.<String, String>opsForHash().get(key, "dwell_seconds")).isEqualTo("30");
  }

  @Test
  void anchorsANewStepAtTheNewestRecordedTime() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant openedAt = Instant.now().plusSeconds(600);

    send(event(user, "fr.click").inSession(session).at(openedAt));
    send(event(user, "fr.click").inSession(session).at(openedAt.plusSeconds(120)));

    // An out-of-order event drags last_event_at backwards before a completion opens step two.
    send(event(user, "fr.click").inSession(session).at(openedAt.plusSeconds(30)));
    send(event(user, MILESTONE_ONE).inSession(session).at(openedAt.plusSeconds(60)));
    send(event(user, "fr.click").inSession(session).at(openedAt.plusSeconds(150)));

    String key = sessionKey(session);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "last_event_at"))
                    .isEqualTo(openedAt.plusSeconds(150).toString()));

    // Step two anchored at the newest recorded time, not at the stale one an old event left.
    assertThat(redis.<String, String>opsForHash().get(key, "step_position")).isEqualTo("2");
    assertThat(redis.<String, String>opsForHash().get(key, "dwell_seconds")).isEqualTo("30");
  }

  @Test
  void ignoresStaleActivityForSessionFeatures() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    send(event(user, MILESTONE_ONE).inSession(session).at(completedAt));

    String key = sessionKey(session);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "step_position"))
                    .isEqualTo("2"));

    // The session expired, then stale activity arrived: it must not reopen the session.
    redis.delete(key);

    send(event(user, "fr.click").inSession(session).at(completedAt.minusSeconds(60)));
    send(event(user, "fr.click").inSession(session).at(completedAt.plusSeconds(60)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(key, "last_event"))
                    .isEqualTo("fr.click"));
    assertThat(redis.<String, String>opsForHash().get(key, "started_at"))
        .isEqualTo(completedAt.plusSeconds(60).toString());
  }

  @Test
  void separatesFallbackUserKeysFromSessionKeys() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    // A batch event with no session_id from a user whose hash is UUID-shaped and equal to
    // another user's session_id: the fallback key must not merge into that user's session.
    String collidingHash = session.toString();

    send(event(user, "fr.page_view").inSession(session));
    send(event(collidingHash, "fr.error"));

    String fallbackKey = "session:%s:user:%s".formatted(APP, collidingHash);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(fallbackKey, "errors"))
                    .isEqualTo("1"));
    Map<String, String> features = redis.<String, String>opsForHash().entries(sessionKey(session));
    assertThat(features.get("last_event")).isEqualTo("fr.page_view");
    assertThat(features).doesNotContainKey("errors");
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
    assertThat(endUsers(user)).isZero();
  }

  @Test
  void deadLettersAnEnvelopeMissingReceivedAt() throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();
    // The gateway stamps received_at on every envelope, so its absence marks a poison record.
    String value =
        objectMapper.writeValueAsString(
            Map.of(
                "tenant_id",
                TENANT,
                "app_id",
                APP,
                "id",
                UUID.randomUUID().toString(),
                "event",
                "fr.click",
                "end_user_hash",
                user,
                "timestamp",
                Instant.now().toString()));
    kafkaTemplate.send(EventTopics.EVENTS_RAW, user, value);

    ConsumerRecord<String, String> dead =
        awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> user.equals(record.key()));
    assertThat(dead.value()).isEqualTo(value);
    assertThat(endUsers(user)).isZero();
  }

  private static EventBuilder event(String endUserHash, String name) {
    return new EventBuilder(endUserHash, name);
  }

  /** Builds one events.raw envelope, defaulting every field a test does not name. */
  private static final class EventBuilder {
    private final String endUserHash;
    private final String event;
    private UUID tenantId = UUID.fromString(TENANT);
    private UUID appId = UUID.fromString(APP);
    private UUID id = UUID.randomUUID();
    private @Nullable UUID sessionId;
    private Instant at = Instant.now();
    private @Nullable Map<String, Object> properties;

    private EventBuilder(String endUserHash, String event) {
      this.endUserHash = endUserHash;
      this.event = event;
    }

    private EventBuilder inApp(UUID tenantId, UUID appId) {
      this.tenantId = tenantId;
      this.appId = appId;
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
          tenantId, appId, at, null, id, event, endUserHash, sessionId, at, properties);
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
    return "session:%s:sid:%s".formatted(APP, session);
  }

  /** Where StreamDeduper claims an applied event UUID for 24 hours. */
  private static String claimKey(UUID eventId) {
    return "dedupe:stream-processor:%s:%s".formatted(APP, eventId);
  }

  /** A milestone the founder defines only after events already flowed. */
  private void seedMilestone(String name, int position) throws SQLException {
    try (var connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
          VALUES ('019813f2-0000-7000-8000-0000000000e5', '%s', '%s', '%s', 'Invite a teammate', %d)
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(TENANT, APP, name, position));
    }
  }

  // A tenant, app, and first milestone that exist only once a test decides they should.
  private void seedLateTenant(UUID tenant, UUID app) throws SQLException {
    try (var connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO tenant (id, name) VALUES ('%s', 'Late Tenant')".formatted(tenant));
      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES ('%s', '%s', 'Late App', 'key_%s', 'hmac_late')
          """
              .formatted(app, tenant, app));
      statement.execute(
          """
          INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
          VALUES ('%s', '%s', '%s', '%s', 'Create a task', 1)
          """
              .formatted(UUID.randomUUID(), tenant, app, MILESTONE_ONE));
    }
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

  // The column name comes from test constants, never from input.
  private @Nullable Instant progressTime(String endUserHash, String milestone, String column) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT p.%s
                FROM milestone_progress p
                JOIN end_user u ON u.id = p.end_user_id
                JOIN milestone m ON m.id = p.milestone_id
                WHERE u.external_hash = ? AND m.name = ?
                """
                    .formatted(column))) {
      statement.setString(1, endUserHash);
      statement.setString(2, milestone);
      try (ResultSet row = statement.executeQuery()) {
        OffsetDateTime time = row.next() ? row.getObject(1, OffsetDateTime.class) : null;
        return time == null ? null : time.toInstant();
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
