package com.firstrunhq.knowledge.internal;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard's view of a doc source, mirroring {@code DocSource} in knowledge.graphqls. The
 * schema never reads {@code appId}. The reindex trigger does.
 */
record DocSource(
    UUID id,
    UUID appId,
    String url,
    DocSourceStatus status,
    @Nullable OffsetDateTime lastIndexedAt) {}
