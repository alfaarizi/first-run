package com.firstrunhq.ingestion;

/**
 * The reserved {@code fr.} prefix names the three events the widget captures on its own. The funnel
 * reads page views and errors, and no founder-defined milestone can take one of these names.
 */
public final class AutoCapturedEvents {

  public static final String PAGE_VIEW = "fr.page_view";
  public static final String CLICK = "fr.click";
  public static final String ERROR = "fr.error";

  private AutoCapturedEvents() {}
}
