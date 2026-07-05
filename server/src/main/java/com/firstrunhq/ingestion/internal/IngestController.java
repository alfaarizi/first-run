package com.firstrunhq.ingestion.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IngestController {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  IngestController(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  @PostMapping(path = "/v1/e", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> ingestEvents(
      @RequestHeader("X-FirstRun-Key") String sdkKey,
      @RequestHeader("X-FirstRun-Timestamp") String timestamp,
      @RequestHeader("X-FirstRun-Signature") String signature,
      @Valid @RequestBody EventBatch batch)
      throws JsonProcessingException {
    for (Event event : batch.events()) {
      // Keyed by end_user_hash so a user's events stay ordered.
      kafkaTemplate.send(
          EventTopicsConfiguration.EVENTS_RAW,
          event.endUserHash(),
          objectMapper.writeValueAsString(event));
    }
    return ResponseEntity.accepted().build();
  }
}
