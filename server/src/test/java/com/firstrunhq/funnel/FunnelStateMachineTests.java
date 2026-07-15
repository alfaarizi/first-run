package com.firstrunhq.funnel;

import static com.firstrunhq.ingestion.AutoCapturedEvents.CLICK;
import static com.firstrunhq.ingestion.AutoCapturedEvents.ERROR;
import static com.firstrunhq.ingestion.AutoCapturedEvents.PAGE_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.IntegrationTest;
import com.firstrunhq.funnel.testfixture.EventBuilder;
import com.firstrunhq.funnel.testfixture.EventStreamHarness;
import com.firstrunhq.ingestion.EventTopics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Drives the stream processor end to end through throwaway Redpanda, Postgres, and Redis, covering
 * state machine advance, event-UUID dedupe, session features with their idle expiry, and the poison
 * route to the dead-letter queue.
 */
@IntegrationTest
class FunnelStateMachineTests {

  private static final String TENANT = "019813f2-0000-7000-8000-000000000201";
  private static final String APP = "019813f2-0000-7000-8000-000000000202";
  private static final String MILESTONE_ONE_ID = "019813f2-0000-7000-8000-000000000203";
  private static final String MILESTONE_TWO_ID = "019813f2-0000-7000-8000-000000000204";
  private static final String INVITE_MILESTONE_ID = "019813f2-0000-7000-8000-000000000205";
  private static final String MILESTONE_ONE = "task_created";
  private static final String MILESTONE_TWO = "report_shared";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final DataSource dataSource;
  private final StringRedisTemplate redis;
  private final EventStreamHarness stream;

  FunnelStateMachineTests(
      KafkaTemplate<String, String> kafkaTemplate,
      ConsumerFactory<String, String> consumerFactory,
      ObjectMapper objectMapper,
      DataSource dataSource,
      StringRedisTemplate redis) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.dataSource = dataSource;
    this.redis = redis;
    this.stream =
        new EventStreamHarness(
            kafkaTemplate, consumerFactory, objectMapper, dataSource, redis, TENANT, APP);
  }

  @BeforeEach
  void seedTenantAppAndMilestones() throws SQLException {
    stream.seedTenant("Funnel Tenant");
    stream.seedApp("Funnel App");
    stream.seedMilestone(MILESTONE_ONE_ID, MILESTONE_ONE, "Create a task", 1);
    stream.seedMilestone(MILESTONE_TWO_ID, MILESTONE_TWO, "Share a report", 2);
  }

  @Test
  void advancesAUserThroughTheStateMachine() throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();

    stream.view(user, session, "/home").send();
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
    assertThat(state(user, MILESTONE_TWO)).isNull();

    stream.event(user, MILESTONE_ONE).inSession(session).send();
    awaitState(user, MILESTONE_ONE, "COMPLETED");
    assertThat(state(user, MILESTONE_TWO)).isNull();

    stream.event(user, CLICK).inSession(session).send();
    awaitState(user, MILESTONE_TWO, "IN_PROGRESS");
    assertThat(state(user, MILESTONE_ONE)).isEqualTo("COMPLETED");

    stream.event(user, MILESTONE_TWO).inSession(session).send();
    awaitState(user, MILESTONE_TWO, "COMPLETED");
    assertThat(endUsers(user)).isEqualTo(1);
  }

  @Test
  void completesAMilestoneOutOfOrder() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();

    // The completion event touches only its milestone, so the skipped step stays PENDING.
    stream.event(user, MILESTONE_TWO).send();
    awaitState(user, MILESTONE_TWO, "COMPLETED");
    assertThat(state(user, MILESTONE_ONE)).isNull();
  }

  @Test
  void anEventOlderThanTheMilestoneDoesNotCompleteIt() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();

    // A completion-named event that predates the milestone reads as any other activity, so a
    // replay against a grown catalog never completes milestones retroactively.
    stream.event(user, MILESTONE_TWO).at(Instant.now().minus(Duration.ofHours(1))).send();
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
    assertThat(state(user, MILESTONE_TWO)).isNull();
  }

  @Test
  void clampsCompletionTimeToTheStartTime() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();

    // Times sit in the near future so the out-of-order completion still postdates the milestone.
    Instant startedAt = Instant.now().plusSeconds(600);
    stream.event(user, CLICK).at(startedAt).send();
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");

    // The completion carries an earlier client time than the event that opened the step.
    stream.event(user, MILESTONE_ONE).at(startedAt.minusSeconds(300)).send();
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
    stream.event(user, CLICK).at(openedAt).send();
    stream.event(user, MILESTONE_ONE).at(openedAt.plusSeconds(120)).send();
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // The user fired the completion event twice and the earlier copy arrived second.
    stream.event(user, MILESTONE_ONE).at(openedAt.plusSeconds(60)).send();
    await()
        .atMost(EventStreamHarness.TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(progressTime(user, MILESTONE_ONE, "completed_at"))
                    .isEqualTo(openedAt.plusSeconds(60)));
  }

  @Test
  void keepsTheEarliestEntryToTheCurrentStep() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant enteredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    stream.event(user, CLICK).at(enteredAt.plusSeconds(60)).send();
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");

    // The user's earlier activity on the same step arrives late and marks the true entry.
    stream.event(user, CLICK).at(enteredAt).send();
    await()
        .atMost(EventStreamHarness.TIMEOUT)
        .untilAsserted(
            () -> assertThat(progressTime(user, MILESTONE_ONE, "started_at")).isEqualTo(enteredAt));
  }

  @Test
  void backdatesACompletedStepForLateActivityInsideIt() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    stream.event(user, MILESTONE_ONE).at(completedAt).send();
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // Activity from before the completion arrives late, so the entry moves and the completion
    // stays.
    stream.event(user, CLICK).at(completedAt.minusSeconds(90)).send();
    await()
        .atMost(EventStreamHarness.TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(progressTime(user, MILESTONE_ONE, "started_at"))
                    .isEqualTo(completedAt.minusSeconds(90)));
    assertThat(progressTime(user, MILESTONE_ONE, "completed_at")).isEqualTo(completedAt);
    assertThat(state(user, MILESTONE_TWO)).isNull();
  }

  @Test
  void backdatesOnlyOneStepWhenCompletionsTie() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);

    // A batch completes both milestones at the same instant, so their completion windows tie.
    stream.event(user, MILESTONE_ONE).at(completedAt).send();
    stream.event(user, MILESTONE_TWO).at(completedAt).send();
    awaitState(user, MILESTONE_TWO, "COMPLETED");

    // Stale activity before both belongs to step one alone, so only its entry moves.
    stream.event(user, CLICK).at(completedAt.minusSeconds(90)).send();
    await()
        .atMost(EventStreamHarness.TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(progressTime(user, MILESTONE_ONE, "started_at"))
                    .isEqualTo(completedAt.minusSeconds(90)));
    assertThat(progressTime(user, MILESTONE_TWO, "started_at")).isEqualTo(completedAt);
  }

  @Test
  void opensTheCurrentStepForActivityBeforeAnOutOfPositionCompletion()
      throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);

    // Completing position two first is legitimate, and position one stays the current step.
    stream.event(user, MILESTONE_TWO).at(completedAt).send();
    awaitState(user, MILESTONE_TWO, "COMPLETED");

    // Activity from before that completion belongs to the still-pending step one, not step two.
    stream.event(user, CLICK).at(completedAt.minusSeconds(90)).send();
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
    assertThat(progressTime(user, MILESTONE_ONE, "started_at"))
        .isEqualTo(completedAt.minusSeconds(90));

    // Step two keeps its own instant completion, and the stale click never backdated it.
    assertThat(progressTime(user, MILESTONE_TWO, "started_at")).isEqualTo(completedAt);
  }

  @Test
  void ignoresActivityFromBeforeACompletion() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    stream.event(user, MILESTONE_ONE).at(completedAt).send();
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    // A distinct late event from before the completion is activity on the finished step.
    stream.event(user, CLICK).at(completedAt.minusSeconds(60)).send();
    stream.event(user, CLICK).at(completedAt.plusSeconds(60)).send();
    awaitState(user, MILESTONE_TWO, "IN_PROGRESS");

    // Step two opened with the fresh event, not the stale one that arrived first.
    assertThat(progressTime(user, MILESTONE_TWO, "started_at"))
        .isEqualTo(completedAt.plusSeconds(60));
  }

  @Test
  void skipsARedeliveredEventUuid() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    EventBuilder error = stream.event(user, ERROR).inSession(session);

    error.send();
    error.send();

    // The trailing click shares the user's partition key, so it arrives after both copies.
    stream.event(user, CLICK).inSession(session).send();

    stream.awaitFeature(session, "last_event", CLICK);
    assertThat(stream.feature(session, "errors")).isEqualTo("1");
  }

  @Test
  void aClaimedDuplicateDoesNotCompleteAMilestoneDefinedLater()
      throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();
    EventBuilder invite = stream.event(user, "invite_sent");

    invite.send();
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");

    // The founder names a milestone after the event flowed, then the same record redelivers.
    stream.seedMilestone(INVITE_MILESTONE_ID, "invite_sent", "Invite a teammate", 3);
    invite.send();

    // The trailing completion shares the user's partition key, so it arrives after the duplicate.
    stream.event(user, MILESTONE_ONE).send();
    awaitState(user, MILESTONE_ONE, "COMPLETED");

    assertThat(state(user, "invite_sent")).isNull();
  }

  @Test
  void aReplayedNonMilestoneEventDoesNotAdvanceTheFunnel() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant clickAt = Instant.now();
    EventBuilder click = stream.event(user, CLICK).at(clickAt);

    // opens step one and claims the id
    click.send();
    stream.event(user, MILESTONE_ONE).at(clickAt.plusSeconds(1)).send();

    // a duplicate now, must not open step two
    click.send();
    stream.event(user, MILESTONE_TWO).at(clickAt.plusSeconds(60)).send();
    awaitState(user, MILESTONE_TWO, "COMPLETED");

    // Step two only ever completed. Had the duplicate opened it, started_at would predate that.
    assertThat(progressTime(user, MILESTONE_TWO, "completed_at"))
        .isEqualTo(progressTime(user, MILESTONE_TWO, "started_at"));
  }

  @Test
  void doesNotDoubleCountWhenAClaimIsLost() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    EventBuilder error = stream.event(user, ERROR).inSession(session);

    error.send();
    stream.awaitFeature(session, "errors", "1");

    // A crash after the apply but before the claim loses the claim, so the record redelivers.
    stream.dropClaim(error.id);
    error.send();

    stream.event(user, CLICK).inSession(session).send();
    stream.awaitFeature(session, "last_event", CLICK);

    // The session hash keeps the applied event id, so the replay counts nothing twice.
    assertThat(stream.feature(session, "errors")).isEqualTo("1");
    assertThat(stream.feature(session, "retries")).isNull();
  }

  @Test
  void restoresTheIdleExpiryOnRedelivery() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    EventBuilder click = stream.event(user, CLICK).inSession(session);
    String key = stream.sessionKey(session);

    click.send();
    await()
        .atMost(EventStreamHarness.TIMEOUT)
        .untilAsserted(
            () -> assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isNotNull().isPositive());

    // A crash after the feature write can lose both the expiry and the claim. The redelivery is
    // a no-op for the counters but must restore the expiry, or the session outlives its window.
    redis.persist(key);
    stream.dropClaim(click.id);

    click.send();
    await()
        .atMost(EventStreamHarness.TIMEOUT)
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
    EventStreamHarness late =
        stream.forApp(UUID.randomUUID().toString(), UUID.randomUUID().toString());

    // The tenant does not exist yet, so every retry fails and the record dead-letters.
    late.event(user, CLICK).send();
    ConsumerRecord<String, String> dead =
        stream.awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> user.equals(record.key()));

    late.seedTenant("Late Tenant");
    late.seedApp("Late App");
    late.seedMilestone(UUID.randomUUID().toString(), MILESTONE_ONE, "Create a task", 1);

    // The failed apply never claimed the event id, so the replay opens the funnel normally.
    kafkaTemplate.send(EventTopics.EVENTS_RAW, user, dead.value());

    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");
  }

  @Test
  void recordsSessionFeaturesWithAnIdleExpiry() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();

    stream.view(user, session, "/a").send();
    stream.view(user, session, "/b").send();
    stream.view(user, session, "/a").send();
    stream.event(user, ERROR).inSession(session).send();
    stream.event(user, ERROR).inSession(session).send();

    stream.awaitFeature(session, "errors", "2");

    String key = stream.sessionKey(session);
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
    stream.view(user, session, "/a").send();

    // A path that is not a string is dropped whole, so it cannot corrupt counters or throw.
    EventBuilder malformed =
        stream.event(user, PAGE_VIEW).inSession(session).withProperties(Map.of("path", 42));
    malformed.send();

    stream.awaitFeature(session, "last_event_id", malformed.id.toString());
    assertThat(stream.feature(session, "last_path")).isEqualTo("/a");
    assertThat(stream.feature(session, "backtracks")).isNull();
  }

  @Test
  void neverShrinksDwellForAnOutOfOrderEvent() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant openedAt = Instant.now();

    stream.event(user, PAGE_VIEW).inSession(session).at(openedAt).send();
    stream.event(user, CLICK).inSession(session).at(openedAt.plusSeconds(120)).send();

    // A late event carries an older client time and must not shrink the recorded dwell.
    stream.event(user, CLICK).inSession(session).at(openedAt.plusSeconds(30)).send();

    stream.awaitFeature(session, "last_event_at", openedAt.plusSeconds(30).toString());
    assertThat(stream.feature(session, "dwell_seconds")).isEqualTo("120");
  }

  @Test
  void doesNotCountABacktrackForAnOutOfOrderPageView() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant openedAt = Instant.now();

    stream.view(user, session, "/a").at(openedAt).send();
    stream.view(user, session, "/b").at(openedAt.plusSeconds(100)).send();

    // A delayed /a from between the two arrives last, so the true order is /a, /a, /b, no return.
    stream.view(user, session, "/a").at(openedAt.plusSeconds(50)).send();

    stream.awaitFeature(session, "last_event_at", openedAt.plusSeconds(50).toString());

    // The backfill never advanced the path, so no backtrack is fabricated and /b stays current.
    assertThat(stream.feature(session, "backtracks")).isNull();
    assertThat(stream.feature(session, "last_path")).isEqualTo("/b");
  }

  @Test
  void doesNotOpenASessionForAnOldEvent() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();

    // An offline flush or earliest-offset replay delivers activity older than the idle window.
    stream
        .event(user, CLICK)
        .inSession(session)
        .at(Instant.now().minus(Duration.ofMinutes(31)))
        .send();

    // The DB still backfills funnel progress, so its state is the processing signal to wait on.
    awaitState(user, MILESTONE_ONE, "IN_PROGRESS");

    // No live session is fabricated for the stuck gate.
    assertThat(redis.hasKey(stream.sessionKey(session))).isFalse();
  }

  @Test
  void anchorsANewStepAtTheSessionsNewestActivity() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant openedAt = Instant.now().plusSeconds(600);

    stream.event(user, CLICK).inSession(session).at(openedAt).send();

    // A late completion opens step two while carrying a time before the click above.
    stream.event(user, MILESTONE_ONE).inSession(session).at(openedAt.minusSeconds(120)).send();
    stream.event(user, CLICK).inSession(session).at(openedAt.plusSeconds(30)).send();

    stream.awaitFeature(session, "last_event_at", openedAt.plusSeconds(30).toString());

    // Dwell on step two runs from the click that anchored it, not from the stale completion time.
    assertThat(stream.feature(session, "step_position")).isEqualTo("2");
    assertThat(stream.feature(session, "dwell_seconds")).isEqualTo("30");
  }

  @Test
  void anchorsANewStepAtTheNewestRecordedTime() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant openedAt = Instant.now().plusSeconds(600);

    stream.event(user, CLICK).inSession(session).at(openedAt).send();
    stream.event(user, CLICK).inSession(session).at(openedAt.plusSeconds(120)).send();

    // An out-of-order event drags last_event_at backwards before a completion opens step two.
    stream.event(user, CLICK).inSession(session).at(openedAt.plusSeconds(30)).send();
    stream.event(user, MILESTONE_ONE).inSession(session).at(openedAt.plusSeconds(60)).send();
    stream.event(user, CLICK).inSession(session).at(openedAt.plusSeconds(150)).send();

    stream.awaitFeature(session, "last_event_at", openedAt.plusSeconds(150).toString());

    // Step two anchored at the newest recorded time, not at the stale one an old event left.
    assertThat(stream.feature(session, "step_position")).isEqualTo("2");
    assertThat(stream.feature(session, "dwell_seconds")).isEqualTo("30");
  }

  @Test
  void ignoresStaleActivityForSessionFeatures() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);
    stream.event(user, MILESTONE_ONE).inSession(session).at(completedAt).send();

    stream.awaitFeature(session, "step_position", "2");

    // The session expired and stale activity arrived after, which must not reopen the session.
    redis.delete(stream.sessionKey(session));

    stream.event(user, CLICK).inSession(session).at(completedAt.minusSeconds(60)).send();
    stream.event(user, CLICK).inSession(session).at(completedAt.plusSeconds(60)).send();
    stream.awaitFeature(session, "last_event", CLICK);
    assertThat(stream.feature(session, "started_at"))
        .isEqualTo(completedAt.plusSeconds(60).toString());
  }

  @Test
  void separatesFallbackUserKeysFromSessionKeys() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();

    // A batch event with no session_id arrives from a user whose hash is UUID-shaped and equal
    // to another user's session_id, so the fallback key must not merge into that session.
    String collidingHash = session.toString();

    stream.event(user, PAGE_VIEW).inSession(session).send();
    stream.event(collidingHash, ERROR).send();

    String fallbackKey = stream.fallbackSessionKey(collidingHash);
    await()
        .atMost(EventStreamHarness.TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(redis.<String, String>opsForHash().get(fallbackKey, "errors"))
                    .isEqualTo("1"));
    assertThat(stream.feature(session, "last_event")).isEqualTo(PAGE_VIEW);
    assertThat(stream.feature(session, "errors")).isNull();
  }

  @Test
  void deadLettersAPoisonEnvelope() {
    String poison = "poison-" + UUID.randomUUID();
    kafkaTemplate.send(EventTopics.EVENTS_RAW, poison, "not an envelope");

    ConsumerRecord<String, String> dead =
        stream.awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> poison.equals(record.key()));
    assertThat(dead.value()).isEqualTo("not an envelope");
  }

  @Test
  void deadLettersAnEnvelopeMissingARequiredField() throws JsonProcessingException, SQLException {
    String user = "user-" + UUID.randomUUID();

    // A structurally valid envelope with no event,
    // which Jackson accepts and the processor must not.
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
        stream.awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> user.equals(record.key()));
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
                CLICK,
                "end_user_hash",
                user,
                "timestamp",
                Instant.now().toString()));
    kafkaTemplate.send(EventTopics.EVENTS_RAW, user, value);

    ConsumerRecord<String, String> dead =
        stream.awaitRecord(EventTopics.EVENTS_RAW_DLQ, record -> user.equals(record.key()));
    assertThat(dead.value()).isEqualTo(value);
    assertThat(endUsers(user)).isZero();
  }

  private void awaitState(String endUserHash, String milestone, String expected) {
    await()
        .atMost(EventStreamHarness.TIMEOUT)
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

  /** The column name comes from test constants, never from input. */
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
}
