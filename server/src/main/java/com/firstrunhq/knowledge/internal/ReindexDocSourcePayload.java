package com.firstrunhq.knowledge.internal;

import org.jspecify.annotations.Nullable;

/** Mirrors {@code ReindexDocSourcePayload} in knowledge.graphqls. */
record ReindexDocSourcePayload(@Nullable DocSource docSource) {}
