package com.firstrunhq.identity;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Scopes the current transaction to one tenant for row-level security. Tenant-scoped repositories
 * call {@link #scopeTo} first inside their transaction. The transaction-local {@code app.tenant_id}
 * it sets is what each isolation policy matches on, so it never leaks across pooled connections.
 */
@Component
public class TenantContext {

  /**
   * The {@link graphql.GraphQLContext} key holding the requesting tenant's {@link UUID}, absent
   * when the request carries none.
   */
  public static final String TENANT_ID_KEY = "tenantId";

  private final JdbcClient jdbc;

  TenantContext(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Sets {@code app.tenant_id} for the remainder of the current transaction. */
  public void scopeTo(UUID tenantId) {
    jdbc.sql("SELECT set_config('app.tenant_id', :tenant_id, true)")
        .param("tenant_id", tenantId.toString())
        .query()
        .singleRow();
  }
}
