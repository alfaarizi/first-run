package com.firstrunhq.testfixture;

import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Seeds tenant-scoped rows for integration suites. The container's default user is a superuser, so
 * these inserts bypass row-level security, and each tolerates reruns across suites sharing one
 * stack.
 */
public final class TestSeeder {

  private TestSeeder() {}

  public static void tenant(DataSource dataSource, String tenantId, String name)
      throws SQLException {
    execute(
        dataSource,
        "INSERT INTO tenant (id, name) VALUES ('%s', '%s') ON CONFLICT (id) DO NOTHING"
            .formatted(tenantId, name));
  }

  /** Creates the app with keys derived from its id, enough for suites that never authenticate. */
  public static void app(DataSource dataSource, String appId, String tenantId, String name)
      throws SQLException {
    execute(
        dataSource,
        """
        INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key)
        VALUES ('%s', '%s', '%s', 'key_%s', 'hmac_%s')
        ON CONFLICT (id) DO NOTHING
        """
            .formatted(appId, tenantId, name, appId, appId));
  }

  public static void milestone(
      DataSource dataSource,
      String milestoneId,
      String tenantId,
      String appId,
      String name,
      String title,
      int position)
      throws SQLException {
    execute(
        dataSource,
        """
        INSERT INTO milestone (id, tenant_id, app_id, name, title, position)
        VALUES ('%s', '%s', '%s', '%s', '%s', %d)
        ON CONFLICT (id) DO NOTHING
        """
            .formatted(milestoneId, tenantId, appId, name, title, position));
  }

  public static void docSource(
      DataSource dataSource, String sourceId, String tenantId, String appId, String url)
      throws SQLException {
    execute(
        dataSource,
        """
        INSERT INTO doc_source (id, tenant_id, app_id, url)
        VALUES ('%s', '%s', '%s', '%s')
        ON CONFLICT (id) DO NOTHING
        """
            .formatted(sourceId, tenantId, appId, url));
  }

  /** Seeds a chunk with a zero vector, enough for suites that never rank by distance. */
  public static void docChunk(
      DataSource dataSource,
      String chunkId,
      String tenantId,
      String sourceId,
      String crawlId,
      String sourceUrl,
      String content)
      throws SQLException {
    int dimension = 1024;
    String zeroVector = "[" + "0,".repeat(dimension - 1) + "0]";
    execute(
        dataSource,
        """
        INSERT INTO doc_chunk (id, tenant_id, source_id, crawl_id, source_url,
            heading_path, content, embedding)
        VALUES ('%s', '%s', '%s', '%s', '%s', '{}', '%s', '%s')
        ON CONFLICT (id) DO NOTHING
        """
            .formatted(chunkId, tenantId, sourceId, crawlId, sourceUrl, content, zeroVector));
  }

  public static void endUser(
      DataSource dataSource,
      String endUserId,
      String tenantId,
      String appId,
      String externalHash,
      String firstSeenAt)
      throws SQLException {
    execute(
        dataSource,
        """
        INSERT INTO end_user (id, tenant_id, app_id, external_hash, first_seen_at)
        VALUES ('%s', '%s', '%s', '%s', '%s')
        ON CONFLICT (id) DO NOTHING
        """
            .formatted(endUserId, tenantId, appId, externalHash, firstSeenAt));
  }

  private static void execute(DataSource dataSource, String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
