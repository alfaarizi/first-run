package com.firstrunhq.funnel.internal;

import com.firstrunhq.funnel.CandidateTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StuckGateProperties.class)
class CandidateTopicsConfiguration {

  /**
   * Boot's KafkaAdmin creates {@code NewTopic} beans at startup, so the topic exists before the
   * first candidate, and the single-broker local stack keeps a single replica.
   */
  @Bean
  NewTopic interventionCandidatesTopic() {
    return TopicBuilder.name(CandidateTopics.INTERVENTION_CANDIDATES)
        .partitions(1)
        .replicas(1)
        .build();
  }

  @Bean
  NewTopic interventionCandidatesDlqTopic() {
    return TopicBuilder.name(CandidateTopics.INTERVENTION_CANDIDATES_DLQ)
        .partitions(1)
        .replicas(1)
        .build();
  }
}
