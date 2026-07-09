package com.firstrunhq.funnel.internal;

import java.time.OffsetDateTime;
import java.util.List;

/** Mirrors {@code Funnel} in api/graphql/funnel.graphqls. */
record Funnel(OffsetDateTime from, OffsetDateTime to, List<FunnelStep> steps) {}
