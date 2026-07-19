package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The request body of {@code POST /v1/messages} (api/openapi/messages.yaml). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record MessageBody(
    @Nullable UUID id,
    @Nullable UUID sessionId,
    @Nullable String endUserHash,
    @Nullable String text,
    @Nullable UUID ref) {}
