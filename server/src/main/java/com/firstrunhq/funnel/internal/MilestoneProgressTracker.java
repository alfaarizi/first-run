package com.firstrunhq.funnel.internal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.firstrunhq.identity.TenantContext;
import com.firstrunhq.ingestion.EventEnvelope;
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
 * milestone predates it, and any other event no older than the user's newest completion starts the
 * current step.
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

  /** Applies the event and returns the user's current step position, absent as {@code null}. */
  @Transactional
  @Nullable Integer advance(EventEnvelope envelope) {
    tenantContext.scopeTo(envelope.tenantId());
    OffsetDateTime eventAt = envelope.timestamp().atOffset(ZoneOffset.UTC);
    UUID endUserId = firstSeenEndUser(envelope, eventAt);
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
    if (completion.isPresent()) {
      complete(envelope.tenantId(), endUserId, completion.get(), eventAt);
    } else {
      startCurrentStep(envelope, endUserId, eventAt);
    }
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

  private UUID firstSeenEndUser(EventEnvelope envelope, OffsetDateTime eventAt) {
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

  // The earliest completion wins when copies of the event arrive out of order, clamped so
  // completed_at never precedes started_at. least and greatest skip a null completed_at.
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

  // A late event from before the user's newest completion is activity on an already-finished
  // step, so it never opens the current one with a start time in the past.
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
              AND :event_at >= coalesce(
                (SELECT max(completed_at) FROM milestone_progress WHERE end_user_id = :end_user_id),
                :event_at)
            ORDER BY m.position
            LIMIT 1
            ON CONFLICT (end_user_id, milestone_id) DO NOTHING
            """)
        .param("tenant_id", envelope.tenantId())
        .param("end_user_id", endUserId)
        .param("app_id", envelope.appId())
        .param("event_at", eventAt)
        .update();
  }
}
