package com.firstrunhq.funnel.internal;

import static com.firstrunhq.ingestion.AutoCapturedEvents.CLICK;
import static org.assertj.core.api.Assertions.assertThat;

import com.firstrunhq.IntegrationTest;
import com.firstrunhq.ingestion.EventEnvelope;
import com.firstrunhq.testfixture.TestSeeder;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link MilestoneProgressTracker#advance} directly for the one contract the stream hides: a
 * redelivered event reads the same staleness its first delivery read, so a crash between the
 * progress write and the session write never flips the session gate and drops the event.
 */
@IntegrationTest
class MilestoneProgressTrackerTests {

  private static final String TENANT = "019813f2-0000-7000-8000-0000000000c1";
  private static final String APP = "019813f2-0000-7000-8000-0000000000c2";
  private static final String MILESTONE_ID = "019813f2-0000-7000-8000-0000000000c3";
  private static final String MILESTONE = "task_created";

  private final MilestoneProgressTracker tracker;
  private final DataSource dataSource;

  MilestoneProgressTrackerTests(MilestoneProgressTracker tracker, DataSource dataSource) {
    this.tracker = tracker;
    this.dataSource = dataSource;
  }

  @BeforeEach
  void seedTenantAppAndMilestone() throws SQLException {
    TestSeeder.tenant(dataSource, TENANT, "Tracker Tenant");
    TestSeeder.app(dataSource, APP, TENANT, "Tracker App");
    TestSeeder.milestone(dataSource, MILESTONE_ID, TENANT, APP, MILESTONE, "Create a task", 1);
  }

  @Test
  void aRedeliveredCompletionReadsTheSameStaleness() {
    String user = "user-" + UUID.randomUUID();
    Instant openedAt = Instant.now().plusSeconds(600);
    tracker.advance(envelope(user, CLICK, openedAt));

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
        null,
        at,
        null);
  }
}
