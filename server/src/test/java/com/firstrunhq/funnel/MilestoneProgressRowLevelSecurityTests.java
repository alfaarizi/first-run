package com.firstrunhq.funnel;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstrunhq.IntegrationTest;
import com.firstrunhq.testfixture.RowLevelSecurityProbe;
import com.firstrunhq.testfixture.TestSeeder;
import java.sql.Connection;
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
    TestSeeder.tenant(dataSource, TENANT_A, "Tenant A");
    TestSeeder.tenant(dataSource, TENANT_B, "Tenant B");
    TestSeeder.app(dataSource, APP_A, TENANT_A, "App A");
    TestSeeder.milestone(
        dataSource, MILESTONE_A, TENANT_A, APP_A, "report_shared", "Share a report", 1);
    TestSeeder.endUser(
        dataSource, END_USER_A, TENANT_A, APP_A, "hash-progress-a", "2026-01-01T00:00:00Z");
    seedMilestoneProgress();

    // SET ROLE and the tenant GUC are session state, so everything shares a connection.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      RowLevelSecurityProbe.grantSelect(statement, "end_user", "milestone_progress");
      statement.execute("SET ROLE rls_probe");

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_A));
      assertThat(RowLevelSecurityProbe.count(statement, "end_user")).isEqualTo(1);
      assertThat(RowLevelSecurityProbe.count(statement, "milestone_progress")).isEqualTo(1);

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_B));
      assertThat(RowLevelSecurityProbe.count(statement, "end_user")).isZero();
      assertThat(RowLevelSecurityProbe.count(statement, "milestone_progress")).isZero();

      statement.execute("RESET app.tenant_id");
      assertThat(RowLevelSecurityProbe.count(statement, "end_user")).isZero();
      assertThat(RowLevelSecurityProbe.count(statement, "milestone_progress")).isZero();

      statement.execute("RESET ROLE");
    }
  }

  private void seedMilestoneProgress() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          INSERT INTO milestone_progress (tenant_id, end_user_id, milestone_id, state, started_at)
          VALUES ('%s', '%s', '%s', 'IN_PROGRESS', now())
          ON CONFLICT (end_user_id, milestone_id) DO NOTHING
          """
              .formatted(TENANT_A, END_USER_A, MILESTONE_A));
    }
  }
}
