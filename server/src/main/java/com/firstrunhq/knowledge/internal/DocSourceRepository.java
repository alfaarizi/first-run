package com.firstrunhq.knowledge.internal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.firstrunhq.identity.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes {@code doc_source} rows under the tenant's RLS scope. */
@Component
class DocSourceRepository {

  private static final NoArgGenerator UUID_V7 = Generators.timeBasedEpochRandomGenerator();

  private static final String COLUMNS = "id, app_id, url, status, last_indexed_at";

  private final JdbcClient jdbc;
  private final TenantContext tenantContext;

  DocSourceRepository(JdbcClient jdbc, TenantContext tenantContext) {
    this.jdbc = jdbc;
    this.tenantContext = tenantContext;
  }

  /** Registers the URL for the app, returning the existing row when it is already registered. */
  @Transactional
  DocSource upsert(UUID tenantId, UUID appId, String url) {
    tenantContext.scopeTo(tenantId);
    return jdbc.sql(
            """
            INSERT INTO doc_source (id, tenant_id, app_id, url)
            VALUES (:id, :tenant_id, :app_id, :url)
            ON CONFLICT (app_id, url) DO UPDATE SET url = EXCLUDED.url
            RETURNING
            """
                + COLUMNS)
        .param("id", UUID_V7.generate())
        .param("tenant_id", tenantId)
        .param("app_id", appId)
        .param("url", url)
        .query(DocSource.class)
        .single();
  }

  /** Returns the tenant's doc source by id, a foreign or unknown id as empty. */
  @Transactional(readOnly = true)
  Optional<DocSource> find(UUID tenantId, UUID id) {
    tenantContext.scopeTo(tenantId);
    return jdbc.sql("SELECT " + COLUMNS + " FROM doc_source WHERE id = :id")
        .param("id", id)
        .query(DocSource.class)
        .optional();
  }

  /** Returns the app's doc sources in registration order. */
  @Transactional(readOnly = true)
  List<DocSource> findByApp(UUID tenantId, UUID appId) {
    tenantContext.scopeTo(tenantId);
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM doc_source WHERE app_id = :app_id ORDER BY created_at")
        .param("app_id", appId)
        .query(DocSource.class)
        .list();
  }

  /** Counts the source's live indexed chunks. */
  @Transactional(readOnly = true)
  int chunkCount(UUID tenantId, UUID sourceId) {
    tenantContext.scopeTo(tenantId);
    // A reindex stages the next generation beside the live one, so counting
    // both would inflate the number until the crawl publishes.
    return jdbc.sql(
            """
            SELECT count(*) FROM doc_chunk c
            JOIN doc_source s ON s.id = c.source_id
            WHERE c.source_id = :source_id AND c.crawl_id = s.live_crawl_id
            """)
        .param("source_id", sourceId)
        .query(Integer.class)
        .single();
  }
}
