package com.firstrunhq;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Throwaway Postgres, Redpanda, and Redis matching the compose stack, wired by service connections.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    // The superuser creates pgvector before Flyway connects
    container.withInitScript("init-pgvector.sql");
    return container;
  }

  /**
   * Provides the Redis container. A GenericContainer exposes no image name, so its
   * service-connection name is set explicitly.
   */
  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7"));
    container.addExposedPort(6379);
    return container;
  }

  @Bean
  @ServiceConnection
  RedpandaContainer redpandaContainer() {
    return new RedpandaContainer(
        DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v26.1.12"));
  }
}
