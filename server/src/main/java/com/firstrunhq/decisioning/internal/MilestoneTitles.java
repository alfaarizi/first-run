package com.firstrunhq.decisioning.internal;

import com.firstrunhq.identity.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads the founder-written milestone title the nudge copy names. */
@Component
class MilestoneTitles {

  private final TenantContext tenantContext;
  private final JdbcClient jdbc;

  MilestoneTitles(TenantContext tenantContext, JdbcClient jdbc) {
    this.tenantContext = tenantContext;
    this.jdbc = jdbc;
  }

  /** Returns the milestone's title, empty when the catalog dropped it since the flag. */
  @Transactional(readOnly = true)
  Optional<String> find(UUID tenantId, UUID milestoneId) {
    tenantContext.scopeTo(tenantId);
    return jdbc.sql("SELECT title FROM milestone WHERE id = :id")
        .param("id", milestoneId)
        .query(String.class)
        .optional();
  }
}
