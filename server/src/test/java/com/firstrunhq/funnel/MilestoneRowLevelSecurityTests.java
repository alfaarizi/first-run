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
 * Proves the V3 row-level security policy isolates milestones. A non-superuser role scoped to one
 * tenant sees nothing of another, and an unset tenant context sees nothing at all.
 */
@IntegrationTest
class MilestoneRowLevelSecurityTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-0000000000d1";
  private static final String TENANT_B = "019813f2-0000-7000-8000-0000000000d2";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000d3";
  private static final String MILESTONE_A = "019813f2-0000-7000-8000-0000000000d4";

  private final DataSource dataSource;

  MilestoneRowLevelSecurityTests(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  void crossTenantReadReturnsNothing() throws SQLException {
    TestSeeder.tenant(dataSource, TENANT_A, "Tenant A");
    TestSeeder.tenant(dataSource, TENANT_B, "Tenant B");
    TestSeeder.app(dataSource, APP_A, TENANT_A, "App A");
    TestSeeder.milestone(
        dataSource, MILESTONE_A, TENANT_A, APP_A, "project_created", "Create a project", 1);

    // SET ROLE and the tenant GUC are session state, so everything shares a connection.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      RowLevelSecurityProbe.grantSelect(statement, "milestone");
      statement.execute("SET ROLE rls_probe");

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_A));
      assertThat(RowLevelSecurityProbe.count(statement, "milestone")).isEqualTo(1);

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_B));
      assertThat(RowLevelSecurityProbe.count(statement, "milestone")).isZero();

      statement.execute("RESET app.tenant_id");
      assertThat(RowLevelSecurityProbe.count(statement, "milestone")).isZero();

      statement.execute("RESET ROLE");
    }
  }
}
