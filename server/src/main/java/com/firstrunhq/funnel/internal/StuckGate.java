package com.firstrunhq.funnel.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.firstrunhq.funnel.CandidateEnvelope;
import com.firstrunhq.funnel.CandidateTopics;
import com.firstrunhq.ingestion.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The deterministic in-stream stuck gate. Flags a session's first signal at or over its threshold
 * while a milestone step is open and emits the flagged window to {@code intervention.candidates},
 * so only candidates reach the model policy. The timer and per-rule counters expose the per-event
 * cost and the candidate rate, the two budgeted numbers.
 */
@Component
class StuckGate {

  private static final NoArgGenerator UUID_V7 = Generators.timeBasedEpochRandomGenerator();
  private static final String EVENTS_METRIC = "firstrun.gate.events";
  private static final String CANDIDATES_METRIC = "firstrun.gate.candidates";
  private static final String RULE_TAG = "rule";

  private final StuckGateProperties properties;
  private final SessionFeatureStore sessionFeatures;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final Timer events;

  /** Wires the thresholds, the candidate producer, and the gate's two metrics. */
  StuckGate(
      StuckGateProperties properties,
      SessionFeatureStore sessionFeatures,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.sessionFeatures = sessionFeatures;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.events = meterRegistry.timer(EVENTS_METRIC);
  }

  /**
   * Runs the gate over one applied event and emits at most one candidate. The broker acknowledges
   * before the caller claims the event, so a produce failure retries the record and a crash between
   * the two redelivers a copy instead of dropping the candidate.
   */
  void flag(
      EventEnvelope envelope,
      MilestoneProgressTracker.@Nullable CurrentStep currentStep,
      @Nullable Map<String, String> features,
      @Nullable String recordKey)
      throws JsonProcessingException {
    long startedNanos = System.nanoTime();
    try {
      // A session flags once, so the candidate marker mutes it however far the signals grow.
      if (features == null
          || currentStep == null
          || features.containsKey(SessionFeatureStore.CANDIDATE_ID)) {
        return;
      }
      CandidateEnvelope.SessionFeatures snapshot = snapshot(features);
      CandidateEnvelope.Rule rule = firingRule(snapshot);
      if (rule == null) {
        return;
      }
      CandidateEnvelope candidate =
          new CandidateEnvelope(
              UUID_V7.generate(),
              envelope.tenantId(),
              envelope.appId(),
              envelope.endUserHash(),
              envelope.sessionId(),
              envelope.id(),
              currentStep.id(),
              currentStep.name(),
              rule,
              snapshot,
              envelope.timestamp());
      kafkaTemplate
          .send(
              CandidateTopics.INTERVENTION_CANDIDATES,
              recordKey != null ? recordKey : envelope.endUserHash(),
              objectMapper.writeValueAsString(candidate))
          .join();
      sessionFeatures.markFlagged(envelope, candidate.id());
      meterRegistry
          .counter(CANDIDATES_METRIC, RULE_TAG, rule.name().toLowerCase(Locale.ROOT))
          .increment();
    } finally {
      events.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Returns the first signal at or over its threshold, checked in a fixed order so a tie always
   * reports the same rule, or {@code null} when none crossed.
   */
  private CandidateEnvelope.@Nullable Rule firingRule(CandidateEnvelope.SessionFeatures features) {
    if (features.errorCount() >= properties.errorsThreshold()) {
      return CandidateEnvelope.Rule.ERRORS;
    }
    if (features.dwellSeconds() >= properties.dwellThreshold().toSeconds()) {
      return CandidateEnvelope.Rule.DWELL;
    }
    if (features.backtrackCount() >= properties.backtracksThreshold()) {
      return CandidateEnvelope.Rule.BACKTRACKS;
    }
    return null;
  }

  /** Copies the session hash into the envelope's typed feature record. */
  private static CandidateEnvelope.SessionFeatures snapshot(Map<String, String> features) {
    return new CandidateEnvelope.SessionFeatures(
        signal(features, SessionFeatureStore.DWELL_SECONDS),
        (int) signal(features, SessionFeatureStore.RETRIES),
        (int) signal(features, SessionFeatureStore.BACKTRACKS),
        (int) signal(features, SessionFeatureStore.ERRORS),
        features.get(SessionFeatureStore.LAST_PATH));
  }

  /** Reads one counter off the session hash, absent as zero. */
  private static long signal(Map<String, String> features, String field) {
    return Long.parseLong(features.getOrDefault(field, "0"));
  }
}
