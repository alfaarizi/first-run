package com.firstrunhq.decisioning.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.firstrunhq.funnel.CandidateEnvelope;
import com.firstrunhq.funnel.CandidateTopics;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * Drives the listener directly for the contract the stream tests cannot reach: a claim write that
 * fails after a delivered push must not fail the record, because the shared error handler would
 * replay the listener and push the same nudge again.
 */
class CandidateProcessorTests {

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
  private final Holdouts holdouts = mock(Holdouts.class);
  private final CandidateDeduper deduper = mock(CandidateDeduper.class);
  private final MilestoneTitles milestoneTitles = mock(MilestoneTitles.class);
  private final NudgeStreams streams = mock(NudgeStreams.class);
  private final CandidateProcessor processor =
      new CandidateProcessor(objectMapper, holdouts, deduper, milestoneTitles, streams);

  @Test
  void acksTheRecordWhenTheClaimWriteFailsAfterADeliveredPush() throws Exception {
    when(milestoneTitles.find(any(), any())).thenReturn(Optional.empty());
    when(streams.pushNudge(any(), any(), any(), any())).thenReturn(true);
    doThrow(new RedisConnectionFailureException("redis unavailable"))
        .when(deduper)
        .claim(any(), any());

    assertThatCode(() -> processor.onCandidate(candidateRecord())).doesNotThrowAnyException();
  }

  @Test
  void failsTheRecordWhenTheClaimCheckFailsBeforeAnyPush() {
    when(deduper.isClaimed(any(), any()))
        .thenThrow(new RedisConnectionFailureException("redis unavailable"));

    // No push has happened yet, so the record keeps the retry-then-dead-letter path.
    assertThatThrownBy(() -> processor.onCandidate(candidateRecord()))
        .isInstanceOf(RedisConnectionFailureException.class);
  }

  @Test
  void dropsAHoldoutCandidateBeforeAnyClaimOrPush() throws Exception {
    when(holdouts.contains(any(), any())).thenReturn(true);

    processor.onCandidate(candidateRecord());

    verifyNoInteractions(deduper, streams);
  }

  private ConsumerRecord<String, String> candidateRecord() throws JsonProcessingException {
    CandidateEnvelope candidate =
        new CandidateEnvelope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "user-1",
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "task_created",
            CandidateEnvelope.Rule.ERRORS,
            new CandidateEnvelope.SessionFeatures(120, 0, 0, 3, "/"),
            Instant.now());
    return new ConsumerRecord<>(
        CandidateTopics.INTERVENTION_CANDIDATES,
        0,
        0,
        "user-1",
        objectMapper.writeValueAsString(candidate));
  }
}
