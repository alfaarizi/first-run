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
    // Jackson does not enforce the record's non-null components, so a malformed envelope is
    // rejected to the dead-letter queue before any side effect.
    requireComplete(envelope);
    // The claim gates the step start and feature counters against a late duplicate, and lands
    // only after those writes are durable, so a crash replays the event instead of dropping it.
    boolean firstDelivery = !deduper.seen(envelope.appId(), envelope.id());
    Integer currentStep = progressTracker.advance(envelope, firstDelivery);
    if (firstDelivery) {
      sessionFeatures.record(envelope, currentStep);
      deduper.claim(envelope.appId(), envelope.id());
    }
  }

  private static void requireComplete(EventEnvelope envelope) {
    if (envelope.tenantId() == null
        || envelope.appId() == null
        || envelope.receivedAt() == null
        || envelope.id() == null
        || envelope.event() == null
        || envelope.endUserHash() == null
        || envelope.timestamp() == null) {
      throw new IllegalArgumentException("events.raw envelope is missing a required field");
    }
  }
}
