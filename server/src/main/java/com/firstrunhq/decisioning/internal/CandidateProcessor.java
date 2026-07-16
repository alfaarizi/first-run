package com.firstrunhq.decisioning.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.funnel.CandidateEnvelope;
import com.firstrunhq.funnel.CandidateTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code intervention.candidates} and pushes each candidate to the user's open widget
 * streams as a nudge, so the stuck gate's thresholds are the only suppression. A malformed record
 * fails before any push, so the shared error handler retries and then dead-letters it.
 */
@Component
class CandidateProcessor {

  static final String GROUP = "decisioning";

  private final ObjectMapper objectMapper;
  private final CandidateDeduper deduper;
  private final MilestoneTitles milestoneTitles;
  private final NudgeStreams streams;

  CandidateProcessor(
      ObjectMapper objectMapper,
      CandidateDeduper deduper,
      MilestoneTitles milestoneTitles,
      NudgeStreams streams) {
    this.objectMapper = objectMapper;
    this.deduper = deduper;
    this.milestoneTitles = milestoneTitles;
    this.streams = streams;
  }

  @KafkaListener(topics = CandidateTopics.INTERVENTION_CANDIDATES, groupId = GROUP)
  void onCandidate(ConsumerRecord<String, String> record) throws JsonProcessingException {
    CandidateEnvelope candidate = objectMapper.readValue(record.value(), CandidateEnvelope.class);
    requireComplete(candidate);

    // Copies of one flagging share event_id while id differs, so the claim binds to event_id.
    if (deduper.isClaimed(candidate.appId(), candidate.eventId())) {
      return;
    }

    streams.pushNudge(candidate.appId(), candidate.endUserHash(), candidate.id(), copy(candidate));
    deduper.claim(candidate.appId(), candidate.eventId());
  }

  /** Names the step the user is stuck on with the founder's own milestone title. */
  private String copy(CandidateEnvelope candidate) {
    return milestoneTitles
        .find(candidate.tenantId(), candidate.milestoneId())
        .map("Stuck on “%s”? Ask a question and we can help."::formatted)
        .orElse("Need a hand? Ask a question and we can help.");
  }

  private static void requireComplete(CandidateEnvelope candidate) {
    if (candidate.id() == null
        || candidate.tenantId() == null
        || candidate.appId() == null
        || candidate.endUserHash() == null
        || candidate.eventId() == null
        || candidate.milestoneId() == null) {
      throw new IllegalArgumentException("intervention.candidates envelope is missing a field");
    }
  }
}
