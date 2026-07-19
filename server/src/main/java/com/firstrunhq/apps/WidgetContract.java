package com.firstrunhq.apps;

/**
 * The signing headers and shared caps of every widget request (api/openapi/ingest.yaml,
 * messages.yaml, stream.yaml).
 */
public final class WidgetContract {

  public static final String SDK_KEY_HEADER = "X-FirstRun-Key";
  public static final String TIMESTAMP_HEADER = "X-FirstRun-Timestamp";
  public static final String SIGNATURE_HEADER = "X-FirstRun-Signature";
  public static final int END_USER_HASH_MAX_CHARS = 128;

  private WidgetContract() {}
}
