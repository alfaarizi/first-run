package com.firstrunhq.decisioning.internal;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS stays permissive because the stream gates its own callers: the signature binds the end user,
 * and a browser-sent {@code Origin} is checked against the app's allowed origins in the controller.
 */
@Configuration(proxyBeanMethods = false)
class StreamCorsConfiguration implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping(StreamController.PATH)
        .allowedOrigins("*")
        .allowedMethods(HttpMethod.GET.name())
        .maxAge(Duration.ofDays(1).toSeconds());
  }
}
