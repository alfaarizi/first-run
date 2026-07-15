package com.firstrunhq.funnel;

import static com.firstrunhq.ingestion.AutoCapturedEvents.CLICK;
import static com.firstrunhq.ingestion.AutoCapturedEvents.ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.IntegrationTest;
import com.firstrunhq.funnel.testfixture.EventBuilder;
import com.firstrunhq.funnel.testfixture.EventStreamHarness;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Drives the stuck gate end to end through throwaway Redpanda, Postgres, and Redis, covering each
 * rule at its threshold and just under it, the fixed tie order, the open-step requirement, one
 * candidate per session, and silence on a redelivered flagging event.
 */
@IntegrationTest
class StuckGateTests {

  private static final String TENANT = "019813f2-0000-7000-8000-000000000301";
  private static final String APP = "019813f2-0000-7000-8000-000000000302";
  private static final String MILESTONE_ONE_ID = "019813f2-0000-7000-8000-000000000303";
  private static final String MILESTONE_TWO_ID = "019813f2-0000-7000-8000-000000000304";
  private static final String MILESTONE_ONE = "task_created";
  private static final String MILESTONE_TWO = "report_shared";

  private final ObjectMapper objectMapper;
  private final EventStreamHarness stream;

  StuckGateTests(
      KafkaTemplate<String, String> kafkaTemplate,
      ConsumerFactory<String, String> consumerFactory,
      ObjectMapper objectMapper,
      DataSource dataSource,
      StringRedisTemplate redis) {
    this.objectMapper = objectMapper;
    this.stream =
        new EventStreamHarness(
            kafkaTemplate, consumerFactory, objectMapper, dataSource, redis, TENANT, APP);
  }

  @BeforeEach
  void seedTenantAppAndMilestones() throws SQLException {
    stream.seedTenant("Gate Tenant");
    stream.seedApp("Gate App");
    stream.seedMilestone(MILESTONE_ONE_ID, MILESTONE_ONE, "Create a task", 1);
    stream.seedMilestone(MILESTONE_TWO_ID, MILESTONE_TWO, "Share a report", 2);
  }

  @Test
  void flagsRepeatedFailuresAtTheThresholdNotBelowIt() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    stream.event(user, ERROR).inSession(session).at(at).send();
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(1)).send();
    stream.awaitFeature(session, "errors", "2");
    assertThat(candidatesFor(user)).isEmpty();

    EventBuilder third = stream.event(user, ERROR).inSession(session).at(at.plusSeconds(2));
    third.send();

    ConsumerRecord<String, String> record = awaitCandidateRecord(user);
    assertThat(record.key()).isEqualTo(user);

    CandidateEnvelope candidate = parse(record);
    assertThat(candidate.id()).isNotNull();
    assertThat(candidate.tenantId()).isEqualTo(UUID.fromString(TENANT));
    assertThat(candidate.appId()).isEqualTo(UUID.fromString(APP));
    assertThat(candidate.endUserHash()).isEqualTo(user);
    assertThat(candidate.sessionId()).isEqualTo(session);
    assertThat(candidate.eventId()).isEqualTo(third.id);
    assertThat(candidate.milestoneId()).isEqualTo(UUID.fromString(MILESTONE_ONE_ID));
    assertThat(candidate.milestoneName()).isEqualTo(MILESTONE_ONE);
    assertThat(candidate.rule()).isEqualTo(CandidateEnvelope.Rule.ERRORS);
    assertThat(candidate.flagTime()).isEqualTo(at.plusSeconds(2));
    assertThat(candidate.features().errorCount()).isEqualTo(3);
    assertThat(candidate.features().dwellSeconds()).isEqualTo(2);
    assertThat(candidate.features().backtrackCount()).isZero();
    assertThat(candidate.features().currentPage()).isNull();

    // The session carries its candidate, the marker that keeps the gate from firing again.
    assertThat(stream.feature(session, "candidate_id")).isEqualTo(candidate.id().toString());
  }

  @Test
  void flagsDwellAtTheThresholdOnlyOnAnOpenStep() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    stream.event(user, CLICK).inSession(session).at(at).send();
    stream.event(user, CLICK).inSession(session).at(at.plusSeconds(599)).send();
    stream.awaitFeature(session, "dwell_seconds", "599");
    assertThat(candidatesFor(user)).isEmpty();

    stream.event(user, CLICK).inSession(session).at(at.plusSeconds(600)).send();

    CandidateEnvelope candidate = parse(awaitCandidateRecord(user));
    assertThat(candidate.rule()).isEqualTo(CandidateEnvelope.Rule.DWELL);
    assertThat(candidate.features().dwellSeconds()).isEqualTo(600);
  }

  @Test
  void flagsAbandonmentLoopsWithTheCurrentPage() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    stream.view(user, session, "/a").at(at).send();
    stream.view(user, session, "/b").at(at.plusSeconds(1)).send();
    stream.view(user, session, "/a").at(at.plusSeconds(2)).send();
    stream.view(user, session, "/b").at(at.plusSeconds(3)).send();
    stream.awaitFeature(session, "backtracks", "2");
    assertThat(candidatesFor(user)).isEmpty();

    stream.view(user, session, "/a").at(at.plusSeconds(4)).send();

    CandidateEnvelope candidate = parse(awaitCandidateRecord(user));
    assertThat(candidate.rule()).isEqualTo(CandidateEnvelope.Rule.BACKTRACKS);
    assertThat(candidate.features().backtrackCount()).isEqualTo(3);
    assertThat(candidate.features().currentPage()).isEqualTo("/a");
  }

  @Test
  void reportsErrorsWhenTwoRulesCrossOnOneEvent() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    stream.event(user, ERROR).inSession(session).at(at).send();
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(1)).send();
    stream.awaitFeature(session, "errors", "2");

    // The third error also carries the dwell threshold, and the fixed order reports errors.
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(600)).send();

    CandidateEnvelope candidate = parse(awaitCandidateRecord(user));
    assertThat(candidate.rule()).isEqualTo(CandidateEnvelope.Rule.ERRORS);
    assertThat(candidate.features().dwellSeconds()).isEqualTo(600);
  }

  @Test
  void flagsASessionOnceHoweverFarTheSignalsGrow() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    for (int i = 0; i < 4; i++) {
      stream.event(user, ERROR).inSession(session).at(at.plusSeconds(i)).send();
    }

    // Progress to step two carries the cumulative counters over, and the marker still mutes
    // the gate.
    stream.event(user, MILESTONE_ONE).inSession(session).at(at.plusSeconds(4)).send();
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(5)).send();
    stream.awaitFeature(session, "errors", "5");

    assertThat(candidatesFor(user)).hasSize(1);
  }

  @Test
  void flagsAFreshSessionAgain() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    for (int i = 0; i < 3; i++) {
      stream.event(user, ERROR).inSession(first).at(at.plusSeconds(i)).send();
    }
    awaitCandidateRecord(user);

    // The widget rotates the session after 30 idle minutes, and the new session flags on its own.
    for (int i = 0; i < 3; i++) {
      stream.event(user, ERROR).inSession(second).at(at.plusSeconds(10 + i)).send();
    }

    await()
        .atMost(EventStreamHarness.TIMEOUT)
        .untilAsserted(() -> assertThat(candidatesFor(user)).hasSize(2));
    assertThat(candidatesFor(user)).extracting(CandidateEnvelope::sessionId).contains(second);
  }

  @Test
  void skipsARedeliveredFlaggingEvent() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    stream.event(user, ERROR).inSession(session).at(at).send();
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(1)).send();

    EventBuilder third = stream.event(user, ERROR).inSession(session).at(at.plusSeconds(2));
    third.send();
    awaitCandidateRecord(user);

    // A crash after the emit but before the claim loses the claim, so the record redelivers.
    stream.dropClaim(third.id);
    third.send();

    // The trailing click shares the user's partition key, so it arrives after the redelivery.
    stream.event(user, CLICK).inSession(session).at(at.plusSeconds(3)).send();
    stream.awaitFeature(session, "last_event", CLICK);

    assertThat(candidatesFor(user)).hasSize(1);
  }

  @Test
  void neverFlagsWithoutAnOpenStep() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    UUID session = UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    stream.event(user, MILESTONE_ONE).inSession(session).at(at).send();
    stream.event(user, MILESTONE_TWO).inSession(session).at(at.plusSeconds(1)).send();

    // Every signal crosses its threshold, but the funnel is complete, so the gate flags nothing.
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(601)).send();
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(602)).send();
    stream.event(user, ERROR).inSession(session).at(at.plusSeconds(603)).send();
    stream.awaitFeature(session, "errors", "3");

    assertThat(candidatesFor(user)).isEmpty();
  }

  @Test
  void flagsTheFallbackSessionWithoutASessionId() throws JsonProcessingException {
    String user = "user-" + UUID.randomUUID();
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    stream.event(user, ERROR).at(at).send();
    stream.event(user, ERROR).at(at.plusSeconds(1)).send();
    stream.event(user, ERROR).at(at.plusSeconds(2)).send();

    CandidateEnvelope candidate = parse(awaitCandidateRecord(user));
    assertThat(candidate.sessionId()).isNull();
    assertThat(candidate.endUserHash()).isEqualTo(user);
  }

  private CandidateEnvelope parse(ConsumerRecord<String, String> record) {
    try {
      return objectMapper.readValue(record.value(), CandidateEnvelope.class);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private ConsumerRecord<String, String> awaitCandidateRecord(String endUserHash) {
    return stream.awaitRecord(
        CandidateTopics.INTERVENTION_CANDIDATES, record -> endUserHash.equals(record.key()));
  }

  private List<CandidateEnvelope> candidatesFor(String endUserHash) {
    return stream
        .recordsOn(
            CandidateTopics.INTERVENTION_CANDIDATES, record -> endUserHash.equals(record.key()))
        .stream()
        .map(this::parse)
        .toList();
  }
}
