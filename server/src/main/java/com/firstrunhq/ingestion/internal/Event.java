package com.firstrunhq.ingestion.internal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Mirrors {@code Event} in api/openapi/ingest.yaml. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record Event(
    @NotNull UUID id,
    @NotBlank @Size(max = 64) @Pattern(regexp = "^(fr\\.[a-z][a-z0-9_]*|[a-z][a-z0-9_]*)$")
        String event,
    @NotBlank @Size(max = 128) String endUserHash,
    @Nullable UUID sessionId,
    @NotNull Instant timestamp,
    @Size(max = 20) @Nullable Map<String, Object> properties) {}
