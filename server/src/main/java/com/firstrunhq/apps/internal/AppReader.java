package com.firstrunhq.apps.internal;

import com.firstrunhq.apps.App;
import com.firstrunhq.identity.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads apps for the dashboard under the requesting tenant's row-level security scope. */
@Repository
class AppReader {

  private final JdbcClient jdbc;
  private final TenantContext tenantContext;

  AppReader(JdbcClient jdbc, TenantContext tenantContext) {
    this.jdbc = jdbc;
    this.tenantContext = tenantContext;
  }

  /** Returns the tenant's app, or empty when the id is absent or belongs to another tenant. */
  @Transactional(readOnly = true)
  Optional<App> find(UUID tenantId, UUID appId) {
    tenantContext.scopeTo(tenantId);
    return jdbc.sql("SELECT id, name FROM app WHERE id = :id")
        .param("id", appId)
        .query((row, rowNumber) -> new App(row.getObject("id", UUID.class), row.getString("name")))
        .optional();
  }
}
