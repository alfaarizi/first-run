package com.firstrunhq.ingestion.internal;

import com.firstrunhq.apps.WidgetContract;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS stays permissive because the gateway itself enforces origins. The allowlist check answers a
 * disallowed origin with a 403 problem body the page can read, and a preflight cannot be gated per
 * app because it carries the header names but never the SDK key value.
 */
@Configuration(proxyBeanMethods = false)
class IngestCorsConfiguration implements WebMvcConfigurer {

  /** Lets any origin POST the signed ingest headers, since the gateway gates the origin itself. */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping(IngestController.PATH)
        .allowedOrigins("*")
        .allowedMethods(HttpMethod.POST.name())
        .allowedHeaders(
            HttpHeaders.CONTENT_TYPE,
            WidgetContract.SDK_KEY_HEADER,
            WidgetContract.TIMESTAMP_HEADER,
            WidgetContract.SIGNATURE_HEADER)
        .maxAge(Duration.ofDays(1).toSeconds());
  }
}
