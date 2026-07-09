package com.firstrunhq.funnel.internal;

import org.jspecify.annotations.Nullable;

/** Mirrors {@code FunnelStep} in api/graphql/funnel.graphqls. */
record FunnelStep(
    Milestone milestone,
    int entered,
    int completed,
    int stuckSignals,
    @Nullable Integer medianSecondsToComplete) {}
