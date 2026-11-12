package com.switchboard.gateway.provider.mock;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Mock provider (prefix "switchboard.mock").
 *
 * @param enabled     when false, "mock" models are not routed to the Mock provider
 * @param latency     artificial delay per reply (0 = instant), e.g. to imitate a slow model
 * @param failureRate share of calls that fail on purpose, 0.0–1.0, e.g. to test failover
 */
@ConfigurationProperties(prefix = "switchboard.mock")
public record MockProperties(boolean enabled, Duration latency, double failureRate) {

	public MockProperties {
		if (latency == null) {
			latency = Duration.ZERO;
		}
		if (latency.isNegative()) {
			throw new IllegalStateException("switchboard.mock.latency must not be negative");
		}
		if (failureRate < 0 || failureRate > 1) {
			throw new IllegalStateException("switchboard.mock.failure-rate must be between 0.0 and 1.0");
		}
	}
}
