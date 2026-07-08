package com.firstrunhq.funnel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstrunhq.TestcontainersConfiguration;
import com.firstrunhq.ingestion.EventEnvelope;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Drives {@link MilestoneProgressTracker#advance} directly for the one contract the stream hides: a
 * redelivered event reads the same staleness its first delivery read, so a crash between the
 * progress write and the session write never flips the session gate and drops the event.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MilestoneProgressTrackerTests {

  private static final String TENANT = "019813f2-0000-7000-8000-0000000000f1";
  private static final String APP = "019813f2-0000-7000-8000-0000000000f2";
  private static final String MILESTONE = "task_created";

  private final MilestoneProgressTracker tracker;
  private final DataSource dataSource;

  MilestoneProgressTrackerTests(MilestoneProgressTracker tracker, DataSource dataSource) {
    this.tracker = tracker;
    this.dataSource = dataSource;
  }

  @BeforeEach
  void seedTenantAppAndMilestone() throws SQLException {
    // The container's default user is a superuser, so these inserts bypass RLS.
    try (var connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO tenant (id, name) VALUES ('%s', 'Tracker Tenant') ON CONFLICT (id) DO NOTHING"
              .formatted(TENANT));
      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES ('%s', '%s', 'Tracker App', 'key_tracker', 'hmac_tracker')
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(APP, TENANT));
      statement.execute(
          """
          INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
          VALUES ('019813f2-0000-7000-8000-0000000000f3', '%s', '%s', '%s', 'Create a task', 1)
          ON CONFLICT (id) DO NOTHING
          """
              .formatted(TENANT, APP, MILESTONE));
    }
  }

  @Test
  void aRedeliveredCompletionReadsTheSameStaleness() {
    String user = "user-" + UUID.randomUUID();
    Instant openedAt = Instant.now().plusSeconds(600);
    tracker.advance(envelope(user, "fr.click", openedAt));

    // The completion predates the step's entry, so its stored time clamps up past the event's
    // own and becomes the user's newest completion.
    EventEnvelope completion = envelope(user, MILESTONE, openedAt.minusSeconds(300));

    MilestoneProgressTracker.Progress first = tracker.advance(completion);
    assertThat(first.stale()).isFalse();
    assertThat(tracker.advance(completion)).isEqualTo(first);
  }

  private static EventEnvelope envelope(String endUserHash, String event, Instant at) {
    return new EventEnvelope(
        UUID.fromString(TENANT),
        UUID.fromString(APP),
        at,
        null,
        UUID.randomUUID(),
        event,
        endUserHash,
        null,
        at,
        null);
  }
}
