package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.funnel.CandidateEnvelope;
import com.firstrunhq.funnel.CandidateTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code intervention.candidates} and pushes each candidate to the user's open widget
 * streams as a nudge, buffered for reconnect replay. Holdout users are dropped, and otherwise the
 * stuck gate's thresholds are the only suppression. A malformed record fails before any push, so
 * the shared error handler retries and then dead-letters it.
 */
@Component
class CandidateProcessor {

  static final String GROUP = "decisioning";

  private static final Logger log = LoggerFactory.getLogger(CandidateProcessor.class);

  private final ObjectMapper objectMapper;
  private final Holdouts holdouts;
  private final CandidateDeduper deduper;
  private final MilestoneTitles milestoneTitles;
  private final NudgeStreams streams;
  private final NudgeContexts contexts;

  CandidateProcessor(
      ObjectMapper objectMapper,
      Holdouts holdouts,
      CandidateDeduper deduper,
      MilestoneTitles milestoneTitles,
      NudgeStreams streams,
      NudgeContexts contexts) {
    this.objectMapper = objectMapper;
    this.holdouts = holdouts;
    this.deduper = deduper;
    this.milestoneTitles = milestoneTitles;
    this.streams = streams;
    this.contexts = contexts;
  }

  /** Turns one candidate into a nudge, unless the holdout or the dedupe mutes it. */
  @KafkaListener(topics = CandidateTopics.INTERVENTION_CANDIDATES, groupId = GROUP)
  void onCandidate(ConsumerRecord<String, String> record) throws JsonProcessingException {
    CandidateEnvelope candidate = objectMapper.readValue(record.value(), CandidateEnvelope.class);

    requireComplete(candidate);

    // Holdout users are the control group, so no intervention surface ever reaches them.
    if (holdouts.contains(candidate.tenantId(), candidate.endUserHash())) {
      return;
    }

    // Copies of one flagging share event_id while id differs, so the claim binds to event_id.
    if (deduper.isClaimed(candidate.appId(), candidate.eventId())) {
      return;
    }

    // A nudge neither delivered nor buffered stays unclaimed, so a candidate copy can retry it.
    if (streams.pushNudge(
        candidate.appId(), candidate.endUserHash(), candidate.id(), nudgeText(candidate))) {
      contexts.record(
          candidate.appId(),
          candidate.endUserHash(),
          new NudgeContexts.NudgeContext(
              candidate.id(), candidate.milestoneId(), candidate.milestoneName()));
      try {
        deduper.claim(candidate.appId(), candidate.eventId());
      } catch (DataAccessException claimLost) {
        log.warn("nudge delivered {} but its claim write failed", candidate.id(), claimLost);
      }
    }
  }

  /** Names the step the user is stuck on with the founder's own milestone title. */
  private String nudgeText(CandidateEnvelope candidate) {
    return milestoneTitles
        .find(candidate.tenantId(), candidate.milestoneId())
        .map("Stuck on “%s”? Ask a question and we can help."::formatted)
        .orElse("Need a hand? Ask a question and we can help.");
  }

  /** Rejects an envelope missing a required field, before any push. */
  private static void requireComplete(CandidateEnvelope candidate) {
    if (candidate.id() == null
        || candidate.tenantId() == null
        || candidate.appId() == null
        || candidate.endUserHash() == null
        || candidate.eventId() == null
        || candidate.milestoneId() == null
        || candidate.milestoneName() == null) {
      throw new IllegalArgumentException("intervention.candidates envelope is missing a field");
    }
  }
}
