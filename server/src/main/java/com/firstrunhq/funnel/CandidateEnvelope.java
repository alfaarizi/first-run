package com.firstrunhq.funnel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One record on the {@code intervention.candidates} topic, an event window the stuck gate flagged
 * for the policy. At most one candidate leaves a session, keyed like {@code events.raw} so one
 * user's candidates stay ordered. A redelivered flagging event can emit a copy, so consumers dedupe
 * on {@code event_id}, which stays fixed across copies while {@code id} does not. Serialized
 * snake_case.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CandidateEnvelope(
    UUID id,
    UUID tenantId,
    UUID appId,
    String endUserHash,
    @Nullable UUID sessionId,
    UUID eventId,
    UUID milestoneId,
    String milestoneName,
    Rule rule,
    SessionFeatures features,
    Instant flagTime) {

  /** The first stuck signal at or over its threshold, in the gate's fixed check order. */
  public enum Rule {
    @JsonProperty("errors")
    ERRORS,
    @JsonProperty("dwell")
    DWELL,
    @JsonProperty("backtracks")
    BACKTRACKS
  }

  /** The session's stuck signals at flag time, named as the policy contract names them. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SessionFeatures(
      long dwellSeconds,
      int retryCount,
      int backtrackCount,
      int errorCount,
      @Nullable String currentPage) {}
}
