package com.firstrunhq;

import com.firstrunhq.ingestion.EventTopics;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Wires the error handling every module's Kafka listeners share. */
@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

  private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);
  private static final long MAX_RETRIES = 2;

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
                new TopicPartition(failed.topic() + EventTopics.DLQ_SUFFIX, failed.partition()));
    return new DefaultErrorHandler(
        recoverer, new FixedBackOff(RETRY_BACKOFF.toMillis(), MAX_RETRIES));
  }
}
