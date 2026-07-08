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

    // The claim lands only after every write below is durable, so a crash replays the event and
    // a claimed duplicate skips it entirely, never reapplied against a catalog that changed since.
    if (deduper.isClaimed(envelope.appId(), envelope.id())) {
      return;
    }

    MilestoneProgressTracker.Progress progress = progressTracker.advance(envelope);

    // Stale activity only amended history, so it is no live signal for the stuck gate.
    if (!progress.stale()) {
      sessionFeatures.record(envelope, progress.currentStep());
    }

    deduper.claim(envelope.appId(), envelope.id());
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
