package com.firstrunhq.funnel.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.ingestion.EventEnvelope;
import com.firstrunhq.ingestion.EventTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code events.raw}, advances milestone progress, and refreshes session features. A
 * malformed record fails before any write, so the shared error handler retries and then
 * dead-letters it.
 */
@Component
class EventStreamProcessor {

  static final String GROUP = "stream-processor";

  private final ObjectMapper objectMapper;
  private final StreamDeduper deduper;
  private final MilestoneProgressTracker progressTracker;
  private final SessionFeatureStore sessionFeatures;

  EventStreamProcessor(
      ObjectMapper objectMapper,
      StreamDeduper deduper,
      MilestoneProgressTracker progressTracker,
      SessionFeatureStore sessionFeatures) {
    this.objectMapper = objectMapper;
    this.deduper = deduper;
    this.progressTracker = progressTracker;
    this.sessionFeatures = sessionFeatures;
  }

  @KafkaListener(topics = EventTopics.EVENTS_RAW, groupId = GROUP)
  void onEvent(String value) throws JsonProcessingException {
    EventEnvelope envelope = objectMapper.readValue(value, EventEnvelope.class);
    // Idempotent milestone writes run before the claim and replay as no-ops after a crash. The
    // claim guards only non-idempotent feature counters, taken last so a crash never strands it.
    Integer currentStep = progressTracker.advance(envelope);
    if (!deduper.firstDelivery(envelope.appId(), envelope.id())) {
      return;
    }
    try {
      sessionFeatures.record(envelope, currentStep);
    } catch (RuntimeException failure) {
      deduper.release(envelope.appId(), envelope.id());
      throw failure;
    }
  }
}
