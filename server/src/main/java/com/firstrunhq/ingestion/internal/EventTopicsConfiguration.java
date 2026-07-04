package com.firstrunhq.ingestion.internal;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class EventTopicsConfiguration {

  static final String EVENTS_RAW = "events.raw";

  /**
   * Boot's KafkaAdmin creates {@code NewTopic} beans at startup, so the topic exists before the
   * first Tasklet click, and the single-broker local stack keeps a single replica.
   */
  @Bean
  NewTopic eventsRawTopic() {
    return TopicBuilder.name(EVENTS_RAW).partitions(1).replicas(1).build();
  }
}
