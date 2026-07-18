package com.firstrunhq.knowledge;

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
 * Proves the V6 row-level security policy isolates doc sources. A non-superuser role scoped to one
 * tenant sees nothing of another, and an unset tenant context sees nothing at all.
 */
@IntegrationTest
class DocSourceRowLevelSecurityTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-0000000000e1";
  private static final String TENANT_B = "019813f2-0000-7000-8000-0000000000e2";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000e3";
  private static final String SOURCE_A = "019813f2-0000-7000-8000-0000000000e4";

  private final DataSource dataSource;

  DocSourceRowLevelSecurityTests(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  void crossTenantReadReturnsNothing() throws SQLException {
    TestSeeder.tenant(dataSource, TENANT_A, "Tenant A");
    TestSeeder.tenant(dataSource, TENANT_B, "Tenant B");
    TestSeeder.app(dataSource, APP_A, TENANT_A, "App A");
    TestSeeder.docSource(dataSource, SOURCE_A, TENANT_A, APP_A, "https://docs.tasklet.dev");

    // SET ROLE and the tenant GUC are session state, so everything shares a connection.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      RowLevelSecurityProbe.grantSelect(statement, "doc_source");
      statement.execute("SET ROLE rls_probe");
      try {
        statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_A));
        assertThat(RowLevelSecurityProbe.count(statement, "doc_source")).isEqualTo(1);

        statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_B));
        assertThat(RowLevelSecurityProbe.count(statement, "doc_source")).isZero();

        statement.execute("RESET app.tenant_id");
        assertThat(RowLevelSecurityProbe.count(statement, "doc_source")).isZero();
      } finally {
        // A failure above must never return a role-switched connection to the pool.
        statement.execute("DISCARD ALL");
      }
    }
  }
}
