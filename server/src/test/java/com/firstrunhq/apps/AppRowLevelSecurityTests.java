package com.firstrunhq.apps;

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
 * Proves the V1 row-level security policies isolate tenants. A non-superuser role scoped to one
 * tenant sees nothing of another, and an unset tenant context sees nothing at all.
 */
@IntegrationTest
class AppRowLevelSecurityTests {

  private static final String TENANT_A = "019813f2-0000-7000-8000-00000000000a";
  private static final String TENANT_B = "019813f2-0000-7000-8000-00000000000b";
  private static final String TENANT_C = "019813f2-0000-7000-8000-00000000000c";
  private static final String APP_A = "019813f2-0000-7000-8000-0000000000aa";
  private static final String APP_C = "019813f2-0000-7000-8000-0000000000cc";

  private final DataSource dataSource;

  AppRowLevelSecurityTests(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Test
  void crossTenantReadReturnsNothing() throws SQLException {
    TestSeeder.tenant(dataSource, TENANT_A, "Tenant A");
    TestSeeder.tenant(dataSource, TENANT_B, "Tenant B");
    TestSeeder.app(dataSource, APP_A, TENANT_A, "App A");

    // SET ROLE and the tenant GUC are session state, so everything shares a connection.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      RowLevelSecurityProbe.grantSelect(statement, "tenant", "app");
      statement.execute("SET ROLE rls_probe");
      try {
        statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_A));
        assertThat(RowLevelSecurityProbe.count(statement, "app")).isEqualTo(1);

        statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_B));
        assertThat(RowLevelSecurityProbe.count(statement, "app")).isZero();

        statement.execute("RESET app.tenant_id");
        assertThat(RowLevelSecurityProbe.count(statement, "app")).isZero();
      } finally {
        // A failure above must never return a role-switched connection to the pool.
        statement.execute("DISCARD ALL");
      }
    }
  }

  @Test
  void sdkKeyLookupReleasesExactlyItsRow() throws SQLException {
    TestSeeder.tenant(dataSource, TENANT_C, "Tenant C");
    TestSeeder.app(dataSource, APP_C, TENANT_C, "App C");

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      RowLevelSecurityProbe.grantSelect(statement, "tenant", "app");
      statement.execute("SET ROLE rls_probe");
      try {
        statement.execute("RESET app.tenant_id");

        statement.execute("SET app.sdk_key = 'key_%s'".formatted(APP_C));
        assertThat(RowLevelSecurityProbe.count(statement, "app")).isEqualTo(1);

        statement.execute("SET app.sdk_key = 'key_that_matches_nothing'");
        assertThat(RowLevelSecurityProbe.count(statement, "app")).isZero();

        statement.execute("RESET app.sdk_key");
        assertThat(RowLevelSecurityProbe.count(statement, "app")).isZero();
      } finally {
        // A failure above must never return a role-switched connection to the pool.
        statement.execute("DISCARD ALL");
      }
    }
  }
}
