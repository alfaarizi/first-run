package com.firstrunhq.ingestion.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingest tuning owned by operations, not by the wire contract. The bucket fields set a tenant's
 * burst and sustained events per second, and the timeout sets how long the gateway waits for broker
 * acknowledgement before telling the client to retry.
 */
@ConfigurationProperties("firstrun.ingest")
record IngestProperties(long rateCapacity, long rateRefillPerSecond, Duration produceTimeout) {}
