package com.firstrunhq.funnel.internal;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Mirrors {@code Milestone} in api/graphql/funnel.graphqls. */
record Milestone(UUID id, String name, String title, int position, OffsetDateTime createdAt) {}
