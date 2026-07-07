package com.firstrunhq.apps.internal;

import com.firstrunhq.apps.AppDirectory;
import com.firstrunhq.apps.SdkApp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcAppDirectory implements AppDirectory {

  private final JdbcClient jdbc;

  JdbcAppDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The row-level security policy on {@code app} releases a row only when the transaction-local
   * {@code app.sdk_key} setting equals its key, so both statements must share one transaction.
   */
  @Override
  @Transactional(readOnly = true)
  public Optional<SdkApp> findBySdkKey(String sdkKey) {
    jdbc.sql("SELECT set_config('app.sdk_key', :sdk_key, true)")
        .param("sdk_key", sdkKey)
        .query()
        .singleRow();
    return jdbc.sql(
            """
            SELECT id, tenant_id, hmac_key, allowed_origins, allowed_properties
            FROM app WHERE sdk_key = :sdk_key
            """)
        .param("sdk_key", sdkKey)
        .query(JdbcAppDirectory::toSdkApp)
        .optional();
  }

  private static SdkApp toSdkApp(ResultSet row, int rowNumber) throws SQLException {
    return new SdkApp(
        row.getObject("id", UUID.class),
        row.getObject("tenant_id", UUID.class),
        row.getString("hmac_key"),
        Set.copyOf(Arrays.asList((String[]) row.getArray("allowed_origins").getArray())),
        Set.copyOf(Arrays.asList((String[]) row.getArray("allowed_properties").getArray())));
  }
}
