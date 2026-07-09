package com.firstrunhq.apps;

import java.util.Set;
import java.util.UUID;

/**
 * An app's ingest configuration, resolved by its public SDK key.
 * <p>
 * The HMAC key ships in the widget bundle, so it authenticates honest clients and bounds replay
 * but is no secret. {@code allowedOrigins} and {@code allowedProperties} are default-deny, and an
 * empty set rejects every origin and drops every property.
 */
public record SdkApp(
    UUID id,
    UUID tenantId,
    String hmacKey,
    Set<String> allowedOrigins,
    Set<String> allowedProperties) {}
