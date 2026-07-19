package com.firstrunhq.decisioning.internal;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS stays permissive because both endpoints gate their own callers: the signature binds the end
 * user, and a browser-sent {@code Origin} is checked against the app's allowed origins in the
 * controller.
 */
@Configuration(proxyBeanMethods = false)
class WidgetCorsConfiguration implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping(StreamController.PATH)
        .allowedOrigins("*")
        .allowedMethods(HttpMethod.GET.name())
        .maxAge(Duration.ofDays(1).toSeconds());
    registry
        .addMapping(MessagesController.PATH)
        .allowedOrigins("*")
        .allowedMethods(HttpMethod.POST.name())
        .allowedHeaders("*")
        .maxAge(Duration.ofDays(1).toSeconds());
  }
}
