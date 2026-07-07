package com.firstrunhq.ingestion.internal;

import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestProperties.class)
class EventTopicsConfiguration {

  // A dead-letter topic is its source topic with this suffix appended.
  static final String DLQ_SUFFIX = ".dlq";
  static final String EVENTS_RAW = "events.raw";
  static final String EVENTS_RAW_DLQ = EVENTS_RAW + DLQ_SUFFIX;

  private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);
  private static final long MAX_RETRIES = 2;

  /**
   * Boot's KafkaAdmin creates {@code NewTopic} beans at startup, so the topic exists before the
   * first Tasklet click, and the single-broker local stack keeps a single replica.
   */
  @Bean
  NewTopic eventsRawTopic() {
    return TopicBuilder.name(EVENTS_RAW).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic eventsRawDlqTopic() {
    return TopicBuilder.name(EVENTS_RAW_DLQ).partitions(1).replicas(1).build();
  }

  /**
   * Boot wires the sole {@code CommonErrorHandler} bean into every listener container, so each
   * consumer retries twice and then dead-letters to {@code <topic>.dlq} on the same partition. The
   * destination resolver replaces the recoverer's default {@code -dlt} suffix with this repo's
   * {@code .dlq}.
   */
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafkaOperations) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaOperations,
            (failed, exception) ->
                new TopicPartition(failed.topic() + DLQ_SUFFIX, failed.partition()));
    return new DefaultErrorHandler(
        recoverer, new FixedBackOff(RETRY_BACKOFF.toMillis(), MAX_RETRIES));
  }
}
