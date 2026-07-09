package com.firstrunhq.funnel.internal;

import com.firstrunhq.identity.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts funnel entries and completions per milestone from the milestone progress projection.
 * <p>
 * A step counts the cohort that entered inside the range, and a completion counts whenever it happens,
 * so conversion never exceeds 100% per step (Amplitude groups funnel users by when they entered.
 */
@Component
class FunnelReader {

  private final JdbcClient jdbc;
  private final TenantContext tenantContext;

  FunnelReader(JdbcClient jdbc, TenantContext tenantContext) {
    this.jdbc = jdbc;
    this.tenantContext = tenantContext;
  }

  /** Returns one step per milestone in position order, over entries in {@code [from, to)}. */
  @Transactional(readOnly = true)
  List<FunnelStep> read(UUID tenantId, UUID appId, OffsetDateTime from, OffsetDateTime to) {
    tenantContext.scopeTo(tenantId);
    return jdbc.sql(
            """
            SELECT m.id, m.name, m.title, m.position, m.created_at,
                   count(p.end_user_id) AS entered,
                   count(p.end_user_id) FILTER (WHERE p.state = 'COMPLETED') AS completed,
                   round(percentile_cont(0.5) WITHIN GROUP (
                     ORDER BY extract(epoch FROM p.completed_at - p.started_at))
                     FILTER (WHERE p.state = 'COMPLETED'))::int AS median_seconds_to_complete
            FROM milestone m
            LEFT JOIN milestone_progress p
              ON p.milestone_id = m.id AND p.started_at >= :from AND p.started_at < :to
            WHERE m.app_id = :app_id
            GROUP BY m.id, m.name, m.title, m.position, m.created_at
            ORDER BY m.position
            """)
        .param("app_id", appId)
        .param("from", from)
        .param("to", to)
        .query(FunnelReader::toStep)
        .list();
  }

  private static FunnelStep toStep(ResultSet row, int rowNumber) throws SQLException {
    Milestone milestone =
        new Milestone(
            row.getObject("id", UUID.class),
            row.getString("name"),
            row.getString("title"),
            row.getInt("position"),
            row.getObject("created_at", OffsetDateTime.class));

    // No stuck gate exists yet to emit signals, so every step reads zero.
    return new FunnelStep(
        milestone,
        row.getInt("entered"),
        row.getInt("completed"),
        0,
        row.getObject("median_seconds_to_complete", Integer.class));
  }
}
