package com.firstrunhq.funnel.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Stuck-gate thresholds owned by operations, not by the wire contract, one per stuck signal. */
@ConfigurationProperties("firstrun.gate")
record StuckGateProperties(int errorsThreshold, Duration dwellThreshold, int backtracksThreshold) {}
