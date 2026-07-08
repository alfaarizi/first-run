package com.firstrunhq;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Proves the V3 row-level security policy isolates milestones. A non-superuser role scoped to one
 * tenant sees nothing of another, and an unset tenant context sees nothing at all.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MilestoneRowLevelSecurityTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-0000000000d1";
  private static final String TENANT_B = "019813f2-0000-7000-8000-0000000000d2";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000d3";

  private final DataSource dataSource;

  MilestoneRowLevelSecurityTests(DataSource dataSource) {
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
      statement.execute("GRANT SELECT ON milestone TO rls_probe");

      // The container's default user is a superuser, so these inserts bypass RLS.
      statement.execute(
          "INSERT INTO tenant (id, name) VALUES ('%s', 'Tenant A'), ('%s', 'Tenant B')"
              .formatted(TENANT_A, TENANT_B));

      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES ('%s', '%s', 'App A', 'key_milestone_a', 'hmac_milestone_a')
          """
              .formatted(APP_A, TENANT_A));
      statement.execute(
          """
          INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
          VALUES ('019813f2-0000-7000-8000-0000000000d4', '%s', '%s',
                  'project_created', 'Create a project', 1)
          """
              .formatted(TENANT_A, APP_A));

      statement.execute("SET ROLE rls_probe");

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_A));
      assertThat(countMilestones(statement)).isEqualTo(1);

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_B));
      assertThat(countMilestones(statement)).isZero();

      statement.execute("RESET app.tenant_id");
      assertThat(countMilestones(statement)).isZero();

      statement.execute("RESET ROLE");
    }
  }

  private int countMilestones(Statement statement) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM milestone")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
