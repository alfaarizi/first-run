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
 * Pins the answer replay window the integration tests cannot reach: a completed answer replays for
 * a short lifetime so a browser reload heals its slot, then expires so it never resurfaces in a
 * later question's slot. A Redis failure degrades to live-only delivery.
 */
class AnswerReplayBufferTests {

  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ListOperations<String, String> list = mock(ListOperations.class);

  private final AnswerReplayBuffer buffer = new AnswerReplayBuffer(redis, objectMapper);

  @Test
  void replaysAStillLiveAnswerButNotOneThatOutlivedItsWindow() throws Exception {
    DoneFrame live = new DoneFrame("m-live", "Open Settings.", List.of());
    DoneFrame expired = new DoneFrame("m-old", "Stale.", List.of());
    when(redis.opsForList()).thenReturn(list);
    when(list.range(anyString(), anyLong(), anyLong()))
        .thenReturn(
            List.of(
                entry(live, Instant.now().minus(Duration.ofSeconds(30))),
                entry(expired, Instant.now().minus(Duration.ofMinutes(3)))));

    List<DoneFrame> frames = buffer.replay(UUID.randomUUID(), "user-1");

    assertThat(frames).extracting(DoneFrame::messageId).containsExactly("m-live");
  }

  @Test
  void carriesTheFullAnswerTextThroughTheRoundTrip() throws Exception {
    DoneFrame done =
        new DoneFrame(
            "m1", "The answer is 42.", List.of(new DoneFrame.Citation("Docs", "https://x")));
    when(redis.opsForList()).thenReturn(list);
    when(list.range(anyString(), anyLong(), anyLong()))
        .thenReturn(List.of(entry(done, Instant.now())));

    List<DoneFrame> frames = buffer.replay(UUID.randomUUID(), "user-1");

    assertThat(frames).containsExactly(done);
  }

  @Test
  void degradesToLiveOnlyWhenRedisIsDown() {
    when(redis.opsForList()).thenReturn(list);
    doThrow(new RedisConnectionFailureException("redis blip"))
        .when(list)
        .range(anyString(), anyLong(), anyLong());

    assertThat(buffer.replay(UUID.randomUUID(), "user-1")).isEmpty();
  }

  private String entry(DoneFrame frame, Instant at) throws JsonProcessingException {
    return objectMapper.writeValueAsString(new AnswerReplayBuffer.Buffered(at, frame));
  }
}
