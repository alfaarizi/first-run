package com.firstrunhq.funnel;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstrunhq.IntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Proves the V4 row-level security policies isolate end users and their milestone progress. A
 * non-superuser role scoped to one tenant sees nothing of another, and an unset tenant context sees
 * nothing at all.
 */
@IntegrationTest
class MilestoneProgressRowLevelSecurityTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-0000000000f1";
  private static final String TENANT_B = "019813f2-0000-7000-8000-0000000000f2";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000f3";
  private static final String MILESTONE_A = "019813f2-0000-7000-8000-0000000000f4";
  private static final String END_USER_A = "019813f2-0000-7000-8000-0000000000f5";

  private final DataSource dataSource;

  MilestoneProgressRowLevelSecurityTests(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  void crossTenantReadReturnsNothing() throws SQLException {
    // SET ROLE and the tenant GUC are session state, so everything shares a connection.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          DO $$ BEGIN
            IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rls_probe') THEN
              CREATE ROLE rls_probe LOGIN;
            END IF;
          END $$
          """);
      statement.execute("GRANT SELECT ON end_user, milestone_progress TO rls_probe");
      // The container's default user is a superuser, so these inserts bypass RLS.
      statement.execute(
          "INSERT INTO tenant (id, name) VALUES ('%s', 'Tenant A'), ('%s', 'Tenant B')"
              .formatted(TENANT_A, TENANT_B));
      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES ('%s', '%s', 'App A', 'key_progress_a', 'hmac_progress_a')
          """
              .formatted(APP_A, TENANT_A));
      statement.execute(
          """
          INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
          VALUES ('%s', '%s', '%s', 'report_shared', 'Share a report', 1)
          """
              .formatted(MILESTONE_A, TENANT_A, APP_A));
      statement.execute(
          """
          INSERT INTO end_user (id, tenant_id, app_id, external_hash, first_seen_at)
          VALUES ('%s', '%s', '%s', 'hash-progress-a', now())
          """
              .formatted(END_USER_A, TENANT_A, APP_A));
      statement.execute(
          """
          INSERT INTO milestone_progress
            (tenant_id, end_user_id, milestone_id, state, started_at)
          VALUES ('%s', '%s', '%s', 'IN_PROGRESS', now())
          """
              .formatted(TENANT_A, END_USER_A, MILESTONE_A));

      statement.execute("SET ROLE rls_probe");

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_A));
      assertThat(count(statement, "end_user")).isEqualTo(1);
      assertThat(count(statement, "milestone_progress")).isEqualTo(1);

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_B));
      assertThat(count(statement, "end_user")).isZero();
      assertThat(count(statement, "milestone_progress")).isZero();

      statement.execute("RESET app.tenant_id");
      assertThat(count(statement, "end_user")).isZero();
      assertThat(count(statement, "milestone_progress")).isZero();

      statement.execute("RESET ROLE");
    }
  }

  private int count(Statement statement, String table) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + table)) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
