package com.firstrunhq.ingestion.internal;

import com.firstrunhq.ingestion.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestProperties.class)
class EventTopicsConfiguration {

  /**
   * Boot's KafkaAdmin creates {@code NewTopic} beans at startup, so the topic exists before the
   * first Tasklet click, and the single-broker local stack keeps a single replica.
   */
  @Bean
  NewTopic eventsRawTopic() {
    return TopicBuilder.name(EventTopics.EVENTS_RAW).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic eventsRawDlqTopic() {
    return TopicBuilder.name(EventTopics.EVENTS_RAW_DLQ).partitions(1).replicas(1).build();
  }
}
