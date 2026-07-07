package com.firstrunhq.ingestion.internal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Mirrors {@code EventBatch} in api/openapi/ingest.yaml. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record EventBatch(
    @Nullable Instant sentAt, @NotNull @Size(min = 1, max = 50) List<@Valid Event> events) {}
