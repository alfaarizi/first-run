package com.firstrunhq.testfixture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The shared non-superuser role the row-level security suites probe isolation with. The container's
 * default user is a superuser and sees every row, so proving a policy needs this role. Callers
 * grant it, seed as the superuser, then {@code SET ROLE rls_probe} before reading.
 */
public final class RowLevelSecurityProbe {

  private RowLevelSecurityProbe() {}

  /** Creates the role once across concurrent suites and grants it SELECT on the tables. */
  public static void grantSelect(Statement statement, String... tables) throws SQLException {
    statement.execute(
        """
        DO $$ BEGIN
          PERFORM pg_advisory_xact_lock(hashtext('rls_probe'));
          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rls_probe') THEN
            CREATE ROLE rls_probe LOGIN;
          END IF;
          GRANT SELECT ON %s TO rls_probe;
        END $$
        """
            .formatted(String.join(", ", tables)));
  }

  /** The table name comes from test constants, never from input. */
  public static int count(Statement statement, String table) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + table)) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
