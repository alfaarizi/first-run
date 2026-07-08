package com.firstrunhq.funnel.internal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.firstrunhq.identity.TenantContext;
import com.firstrunhq.ingestion.EventEnvelope;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances one user's milestone state machine. An event completes the milestone it names when the
 * milestone predates it, fresh activity starts the user's current step, and stale activity only
 * backdates the start of the step it belonged to.
 */
@Component
class MilestoneProgressTracker {

  private static final NoArgGenerator UUID_V7 = Generators.timeBasedEpochRandomGenerator();

  private final JdbcClient jdbc;
  private final TenantContext tenantContext;

  MilestoneProgressTracker(JdbcClient jdbc, TenantContext tenantContext) {
    this.jdbc = jdbc;
    this.tenantContext = tenantContext;
  }

  /**
   * The current step, {@code null} once every milestone is complete, and whether the event was
   * stale.
   */
  record Progress(@Nullable Integer currentStep, boolean stale) {}

  /** Applies the event and returns the user's progress after it. */
  @Transactional
  Progress advance(EventEnvelope envelope) {
    tenantContext.scopeTo(envelope.tenantId());
    OffsetDateTime eventAt = envelope.timestamp().atOffset(ZoneOffset.UTC);
    UUID endUserId = findOrCreateEndUser(envelope, eventAt);

    // Only a milestone that predates the event completes, so a replayed event takes the decision
    // its first delivery took, whatever the catalog has grown to since.
    Optional<UUID> completion =
        jdbc.sql(
                """
                SELECT id FROM milestone
                WHERE app_id = :app_id AND name = :name AND created_at <= :event_at
                """)
            .param("app_id", envelope.appId())
            .param("name", envelope.event())
            .param("event_at", eventAt)
            .query(UUID.class)
            .optional();

    // Stale activity is from before the user's newest completion: an already-finished step. The
    // row this event completes is excluded, so a redelivery reads the answer its first apply read.
    boolean stale =
        jdbc.sql(
                """
                SELECT max(completed_at) FROM milestone_progress
                WHERE end_user_id = :end_user_id
                  AND milestone_id IS DISTINCT FROM :milestone_id
                """)
            .param("end_user_id", endUserId)
            .param("milestone_id", completion.orElse(null), Types.OTHER)
            .query(OffsetDateTime.class)
            .optional()
            .filter(eventAt::isBefore)
            .isPresent();

    if (completion.isPresent()) {
      complete(envelope.tenantId(), endUserId, completion.get(), eventAt);
    } else if (stale) {
      backdateStep(endUserId, eventAt);
    } else {
      startCurrentStep(envelope, endUserId, eventAt);
    }
    return new Progress(findCurrentStep(envelope, endUserId), stale);
  }

  /** Resolves the end user for the envelope's hash, created with first_seen_at at first sight. */
  private UUID findOrCreateEndUser(EventEnvelope envelope, OffsetDateTime eventAt) {
    Optional<UUID> seen = findEndUser(envelope);
    if (seen.isPresent()) {
      return seen.get();
    }
    return jdbc.sql(
            """
            INSERT INTO end_user (id, tenant_id, app_id, external_hash, first_seen_at)
            VALUES (:id, :tenant_id, :app_id, :external_hash, :first_seen_at)
            ON CONFLICT (app_id, external_hash) DO NOTHING
            RETURNING id
            """)
        .param("id", UUID_V7.generate())
        .param("tenant_id", envelope.tenantId())
        .param("app_id", envelope.appId())
        .param("external_hash", envelope.endUserHash())
        .param("first_seen_at", eventAt)
        .query(UUID.class)
        .optional()
        // A concurrent insert won the conflict, so the row now exists to read.
        .orElseGet(() -> findEndUser(envelope).orElseThrow());
  }

  private Optional<UUID> findEndUser(EventEnvelope envelope) {
    return jdbc.sql(
            "SELECT id FROM end_user WHERE app_id = :app_id AND external_hash = :external_hash")
        .param("app_id", envelope.appId())
        .param("external_hash", envelope.endUserHash())
        .query(UUID.class)
        .optional();
  }

  /**
   * Marks the milestone completed. The earliest completion wins when copies of the event arrive out
   * of order, clamped so completed_at never precedes started_at. least and greatest skip a null
   * completed_at.
   */
  private void complete(UUID tenantId, UUID endUserId, UUID milestoneId, OffsetDateTime eventAt) {
    jdbc.sql(
            """
            INSERT INTO milestone_progress
              (tenant_id, end_user_id, milestone_id, state, started_at, completed_at)
            VALUES (:tenant_id, :end_user_id, :milestone_id, 'COMPLETED', :event_at, :event_at)
            ON CONFLICT (end_user_id, milestone_id) DO UPDATE
            SET state = 'COMPLETED',
                completed_at = greatest(
                  milestone_progress.started_at,
                  least(milestone_progress.completed_at, excluded.completed_at))
            WHERE milestone_progress.state <> 'COMPLETED'
               OR excluded.completed_at < milestone_progress.completed_at
            """)
        .param("tenant_id", tenantId)
        .param("end_user_id", endUserId)
        .param("milestone_id", milestoneId)
        .param("event_at", eventAt)
        .update();
  }

  /**
   * Backdates the entry of the step whose completion window contains the stale event, so late
   * activity can only move that step's started_at earlier, never past the row's own completion.
   */
  private void backdateStep(UUID endUserId, OffsetDateTime eventAt) {
    jdbc.sql(
            """
            UPDATE milestone_progress
            SET started_at = :event_at
            WHERE end_user_id = :end_user_id AND :event_at < started_at
              AND completed_at = (SELECT min(completed_at) FROM milestone_progress
                                  WHERE end_user_id = :end_user_id AND completed_at >= :event_at)
            """)
        .param("end_user_id", endUserId)
        .param("event_at", eventAt)
        .update();
  }

  /**
   * Opens the lowest-position non-completed milestone. Only fresh activity reaches here, and the
   * earliest event on a step is its entry, so a conflict with the existing row (always in progress)
   * can only move started_at earlier.
   */
  private void startCurrentStep(EventEnvelope envelope, UUID endUserId, OffsetDateTime eventAt) {
    jdbc.sql(
            """
            INSERT INTO milestone_progress
              (tenant_id, end_user_id, milestone_id, state, started_at)
            SELECT :tenant_id, :end_user_id, m.id, 'IN_PROGRESS', :event_at
            FROM milestone m
            LEFT JOIN milestone_progress p
              ON p.milestone_id = m.id AND p.end_user_id = :end_user_id
            WHERE m.app_id = :app_id AND (p.state IS NULL OR p.state <> 'COMPLETED')
            ORDER BY m.position
            LIMIT 1
            ON CONFLICT (end_user_id, milestone_id) DO UPDATE
            SET started_at = excluded.started_at
            WHERE excluded.started_at < milestone_progress.started_at
            """)
        .param("tenant_id", envelope.tenantId())
        .param("end_user_id", endUserId)
        .param("app_id", envelope.appId())
        .param("event_at", eventAt)
        .update();
  }

  /** Returns the lowest-position milestone the user has not completed, absent as {@code null}. */
  private @Nullable Integer findCurrentStep(EventEnvelope envelope, UUID endUserId) {
    return jdbc.sql(
            """
            SELECT m.position
            FROM milestone m
            LEFT JOIN milestone_progress p
              ON p.milestone_id = m.id AND p.end_user_id = :end_user_id
            WHERE m.app_id = :app_id AND (p.state IS NULL OR p.state <> 'COMPLETED')
            ORDER BY m.position
            LIMIT 1
            """)
        .param("end_user_id", endUserId)
        .param("app_id", envelope.appId())
        .query(Integer.class)
        .optional()
        .orElse(null);
  }
}
