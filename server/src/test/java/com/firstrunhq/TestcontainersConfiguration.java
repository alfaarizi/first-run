package com.firstrunhq;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/** Throwaway Postgres and Redpanda matching the compose stack, wired by service connections. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
  }

  @Bean
  @ServiceConnection
  RedpandaContainer redpandaContainer() {
    return new RedpandaContainer(
        DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v26.1.12"));
  }
}
