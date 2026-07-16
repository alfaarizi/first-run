package com.firstrunhq.ingestion;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One record on the {@code events.raw} topic. The gateway adds the owning tenant and app, its clock
 * at arrival, and the truncated client IP, and corrects each event {@code timestamp} by the clock
 * skew {@code sent_at} reveals, never past {@code received_at}. Serialized snake_case, keyed by the
 * hex SHA-256 of {@code tenant_id:end_user_hash} so one user's events stay ordered within a
 * partition.
 *
 * <p>{@code ref} names the server-issued entity an intervention event responds to, the nudge or the
 * action execution. It is structural, so it rides beside the properties and never passes the
 * allowlist.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EventEnvelope(
    UUID tenantId,
    UUID appId,
    Instant receivedAt,
    @Nullable String ip,
    UUID id,
    String event,
    String endUserHash,
    @Nullable UUID sessionId,
    @Nullable UUID ref,
    Instant timestamp,
    @Nullable Map<String, Object> properties) {}
