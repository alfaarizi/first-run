package com.firstrunhq;

import graphql.scalars.ExtendedScalars;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/** Wires the scalar implementations the schema in /api declares. */
@Configuration(proxyBeanMethods = false)
class GraphqlConfiguration {

  /** Registers the DateTime scalar, which parses and serializes RFC 3339 offset timestamps. */
  @Bean
  RuntimeWiringConfigurer extendedScalars() {
    return wiring -> wiring.scalar(ExtendedScalars.DateTime);
  }
}
