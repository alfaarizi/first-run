package com.firstrunhq.funnel.testfixture;

import static com.firstrunhq.ingestion.AutoCapturedEvents.PAGE_VIEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstrunhq.testfixture.TestSeeder;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Drives one app's traffic through {@code events.raw} end to end, seeding its tenant rows, sending
 * envelopes, and reading back the session hash, the dedupe claims, and any topic's records.
 */
public final class EventStreamHarness {

  /** How long any await may take before the stack is judged broken. */
  public static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ConsumerFactory<String, String> consumerFactory;
  private final ObjectMapper objectMapper;
  private final DataSource dataSource;
  private final StringRedisTemplate redis;
  private final UUID tenantId;
  private final UUID appId;

  public EventStreamHarness(
      KafkaTemplate<String, String> kafkaTemplate,
      ConsumerFactory<String, String> consumerFactory,
      ObjectMapper objectMapper,
      DataSource dataSource,
      StringRedisTemplate redis,
      String tenantId,
      String appId) {
    this.kafkaTemplate = kafkaTemplate;
    this.consumerFactory = consumerFactory;
    this.objectMapper = objectMapper;
    this.dataSource = dataSource;
    this.redis = redis;
    this.tenantId = UUID.fromString(tenantId);
    this.appId = UUID.fromString(appId);
  }

  /** A sibling harness for another app on the same stack. */
  public EventStreamHarness forApp(String tenantId, String appId) {
    return new EventStreamHarness(
        kafkaTemplate, consumerFactory, objectMapper, dataSource, redis, tenantId, appId);
  }

  public void seedTenant(String name) throws SQLException {
    TestSeeder.tenant(dataSource, tenantId.toString(), name);
  }

  public void seedApp(String name) throws SQLException {
    TestSeeder.app(dataSource, appId.toString(), tenantId.toString(), name);
  }

  public void seedMilestone(String milestoneId, String name, String title, int position)
      throws SQLException {
    TestSeeder.milestone(
        dataSource, milestoneId, tenantId.toString(), appId.toString(), name, title, position);
  }

  public EventBuilder event(String endUserHash, String name) {
    return new EventBuilder(kafkaTemplate, objectMapper, tenantId, appId, endUserHash, name);
  }

  public EventBuilder view(String endUserHash, UUID sessionId, String path) {
    return event(endUserHash, PAGE_VIEW).inSession(sessionId).withProperties(Map.of("path", path));
  }

  /** Where SessionFeatureStore keeps the features of a session with a session_id. */
  public String sessionKey(UUID sessionId) {
    return "session:%s:sid:%s".formatted(appId, sessionId);
  }

  /** Where SessionFeatureStore keeps the features of a session without one. */
  public String fallbackSessionKey(String endUserHash) {
    return "session:%s:user:%s".formatted(appId, endUserHash);
  }

  /** Deletes the 24-hour claim StreamDeduper holds, simulating a crash before it landed. */
  public void dropClaim(UUID eventId) {
    redis.delete("dedupe:stream-processor:%s:%s".formatted(appId, eventId));
  }

  public @Nullable String feature(UUID sessionId, String field) {
    return redis.<String, String>opsForHash().get(sessionKey(sessionId), field);
  }

  public void awaitFeature(UUID sessionId, String field, String expected) {
    Awaitility.await()
        .atMost(TIMEOUT)
        .untilAsserted(() -> assertThat(feature(sessionId, field)).isEqualTo(expected));
  }

  /** Waits for the topic's first matching record, failing after {@link #TIMEOUT} without one. */
  public ConsumerRecord<String, String> awaitRecord(
      String topic, Predicate<ConsumerRecord<String, String>> match) {
    try (Consumer<String, String> consumer = consumerAt(topic)) {
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
          if (match.test(record)) {
            return record;
          }
        }
      }
    }
    throw new AssertionError("no matching record arrived on " + topic);
  }

  /**
   * Reads the matching records already on the topic. A producer that waited for the broker's
   * acknowledgement always sits below the end offsets read here.
   */
  public List<ConsumerRecord<String, String>> recordsOn(
      String topic, Predicate<ConsumerRecord<String, String>> match) {
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (Consumer<String, String> consumer = consumerAt(topic)) {
      Set<TopicPartition> partitions = consumer.assignment();
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      while (partitions.stream()
          .anyMatch(partition -> consumer.position(partition) < end.getOrDefault(partition, 0L))) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
          if (match.test(record)) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }

  /** Assigns the topic directly and rewinds, so no consumer-group rebalance delays the read. */
  private Consumer<String, String> consumerAt(String topic) {
    Consumer<String, String> consumer =
        consumerFactory.createConsumer("harness-" + UUID.randomUUID(), null);
    List<TopicPartition> partitions =
        consumer.partitionsFor(topic).stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
    consumer.assign(partitions);
    consumer.seekToBeginning(partitions);
    return consumer;
  }
}
