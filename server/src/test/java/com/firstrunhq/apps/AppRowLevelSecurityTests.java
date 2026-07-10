package com.firstrunhq.apps;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstrunhq.IntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
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

  private final DataSource dataSource;

  AppRowLevelSecurityTests(DataSource dataSource) {
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
      statement.execute("GRANT SELECT ON tenant, app TO rls_probe");

      // The container's default user is a superuser, so these inserts bypass RLS.
      statement.execute(
          "INSERT INTO tenant (id, name) VALUES ('%s', 'Tenant A'), ('%s', 'Tenant B')"
              .formatted(TENANT_A, TENANT_B));

      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES ('019813f2-0000-7000-8000-0000000000aa', '%s', 'App A', 'key_a', 'hmac_a')
          """
              .formatted(TENANT_A));

      statement.execute("SET ROLE rls_probe");

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_A));
      assertThat(countApps(statement)).isEqualTo(1);

      statement.execute("SET app.tenant_id = '%s'".formatted(TENANT_B));
      assertThat(countApps(statement)).isZero();

      statement.execute("RESET app.tenant_id");
      assertThat(countApps(statement)).isZero();

      statement.execute("RESET ROLE");
    }
  }

  @Test
  void sdkKeyLookupReleasesExactlyItsRow() throws SQLException {
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
      statement.execute("GRANT SELECT ON tenant, app TO rls_probe");
      statement.execute(
          """
          INSERT INTO tenant (id, name)
          VALUES ('019813f2-0000-7000-8000-00000000000c', 'Tenant C')
          ON CONFLICT (id) DO NOTHING
          """);
      statement.execute(
          """
          INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
          VALUES ('019813f2-0000-7000-8000-0000000000cc',
                  '019813f2-0000-7000-8000-00000000000c', 'App C', 'key_c', 'hmac_c')
          ON CONFLICT (id) DO NOTHING
          """);

      statement.execute("SET ROLE rls_probe");
      statement.execute("RESET app.tenant_id");

      statement.execute("SET app.sdk_key = 'key_c'");
      assertThat(countApps(statement)).isEqualTo(1);

      statement.execute("SET app.sdk_key = 'key_that_matches_nothing'");
      assertThat(countApps(statement)).isZero();

      statement.execute("RESET app.sdk_key");
      assertThat(countApps(statement)).isZero();

      statement.execute("RESET ROLE");
    }
  }

  private int countApps(Statement statement) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM app")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
