package com.firstrunhq.decisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Pins the per-frame replay lifetime the integration tests cannot reach: a frame expires 30 minutes
 * after its own buffering, so a later append keeping the Redis key alive never resurfaces a nudge
 * from a session the user has left.
 */
class NudgeReplayBufferTests {

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ListOperations<String, String> list = mock(ListOperations.class);

  private final NudgeReplayBuffer buffer = new NudgeReplayBuffer(redis, objectMapper);

  @Test
  void neverReplaysAFramePastItsOwnLifetime() throws Exception {
    UUID live = UUID.randomUUID();
    UUID expired = UUID.randomUUID();
    when(redis.opsForList()).thenReturn(list);
    when(list.range(anyString(), anyLong(), anyLong()))
        .thenReturn(
            List.of(
                entry(live, Instant.now().minus(Duration.ofMinutes(29))),
                entry(expired, Instant.now().minus(Duration.ofMinutes(31)))));

    List<NudgeFrame> frames = buffer.after(UUID.randomUUID(), "user-1", "earliest");

    assertThat(frames).extracting(NudgeFrame::id).containsExactly(live);
  }

  @Test
  void reportsSuccessOnceThePushLandsEvenWhenMaintenanceFails() {
    when(redis.opsForList()).thenReturn(list);
    when(list.leftPush(anyString(), anyString())).thenReturn(1L);
    doThrow(new RedisConnectionFailureException("redis blip"))
        .when(list)
        .trim(anyString(), anyLong(), anyLong());

    // A false here would let a candidate copy buffer a duplicate nudge for the same event.
    boolean accepted =
        buffer.append(UUID.randomUUID(), "user-1", new NudgeFrame(UUID.randomUUID(), "hi"));

    assertThat(accepted).isTrue();
  }

  private String entry(UUID id, Instant at) throws JsonProcessingException {
    return objectMapper.writeValueAsString(
        new NudgeReplayBuffer.Buffered(at, new NudgeFrame(id, "hi")));
  }
}
