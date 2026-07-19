package com.firstrunhq.funnel.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.ingestion.EventEnvelope;
import com.firstrunhq.ingestion.EventTopics;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code events.raw}, advances milestone progress, refreshes session features, and runs
 * the stuck gate over them. A malformed record fails before any write, so the shared error handler
 * retries and then dead-letters it.
 */
@Component
class EventStreamProcessor {

  static final String GROUP = "stream-processor";

  private final ObjectMapper objectMapper;
  private final StreamDeduper deduper;
  private final MilestoneProgressTracker progressTracker;
  private final SessionFeatureStore sessionFeatures;
  private final StuckGate stuckGate;

  EventStreamProcessor(
      ObjectMapper objectMapper,
      StreamDeduper deduper,
      MilestoneProgressTracker progressTracker,
      SessionFeatureStore sessionFeatures,
      StuckGate stuckGate) {
    this.objectMapper = objectMapper;
    this.deduper = deduper;
    this.progressTracker = progressTracker;
    this.sessionFeatures = sessionFeatures;
    this.stuckGate = stuckGate;
  }

  /** Applies one event: progress, session features, then the gate, claimed last. */
  @KafkaListener(topics = EventTopics.EVENTS_RAW, groupId = GROUP)
  void onEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
    EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

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
    Map<String, String> features =
        progress.stale() ? null : sessionFeatures.record(envelope, progress.currentStep());

    // Candidates inherit the record's key, so one user's candidates stay ordered like the events.
    stuckGate.flag(envelope, progress.currentStep(), features, record.key());

    deduper.claim(envelope.appId(), envelope.id());
  }

  /** Rejects an envelope missing a required field, before any side effect. */
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
