package com.firstrunhq.ingestion;

/**
 * The reserved auto-capture event names. The widget emits these three under its reserved
 * {@code fr.} prefix, and the funnel reads page views and errors. A founder-defined milestone
 * can never take one of these names.
 */
public final class AutoCapturedEvents {

  public static final String PAGE_VIEW = "fr.page_view";
  public static final String CLICK = "fr.click";
  public static final String ERROR = "fr.error";

  private AutoCapturedEvents() {}
}
