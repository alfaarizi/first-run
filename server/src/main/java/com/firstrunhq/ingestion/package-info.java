/**
 * Ingest gateway. Accepts event batches from the widget SDK on {@code POST /v1/e} and produces them
 * to the {@code events.raw} topic.
 */
@ApplicationModule
package com.firstrunhq.ingestion;

import org.springframework.modulith.ApplicationModule;
