package com.firstrunhq.funnel.testfixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.ingestion.EventEnvelope;
import com.firstrunhq.ingestion.EventTopics;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.core.KafkaTemplate;

/** Builds one events.raw envelope, defaulting every field a test does not name. */
public final class EventBuilder {

  public final UUID id = UUID.randomUUID();

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final UUID tenantId;
  private final UUID appId;
  private final String endUserHash;
  private final String event;
  private @Nullable UUID sessionId;
  private Instant at = Instant.now();
  private @Nullable Map<String, Object> properties;

  EventBuilder(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      UUID tenantId,
      UUID appId,
      String endUserHash,
      String event) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.tenantId = tenantId;
    this.appId = appId;
    this.endUserHash = endUserHash;
    this.event = event;
  }

  public EventBuilder inSession(UUID sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  public EventBuilder at(Instant at) {
    this.at = at;
    return this;
  }

  public EventBuilder withProperties(Map<String, Object> properties) {
    this.properties = properties;
    return this;
  }

  /** Keys by the user hash so one user's events stay ordered, like the gateway's partition key. */
  public void send() throws JsonProcessingException {
    EventEnvelope envelope =
        new EventEnvelope(
            tenantId, appId, at, null, id, event, endUserHash, sessionId, null, at, properties);
    kafkaTemplate.send(
        EventTopics.EVENTS_RAW, endUserHash, objectMapper.writeValueAsString(envelope));
  }
}
