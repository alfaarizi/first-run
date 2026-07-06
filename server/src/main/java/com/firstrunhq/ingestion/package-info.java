/**
 * Ingest gateway. Gates event batches from the widget SDK on {@code POST /v1/e} with the origin
 * allowlist, signature, dedupe, property scrub, IP truncation, and per-tenant rate limit, and
 * produces {@link com.firstrunhq.ingestion.EventEnvelope} records to the {@code events.raw} topic.
 */
@ApplicationModule
@NullMarked
package com.firstrunhq.ingestion;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
