package com.switchboard.gateway.resilience;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry, circuit-breaker and failover settings (prefix "switchboard.failover").
 *
 * @param maxAttempts          tries per model for a TRANSIENT failure (1 = no retry)
 * @param retryWait            pause before the first retry; doubles each time
 * @param fallbackModels       models to try next, in order, when a model fails (only ones the member is
 *                             allowed to use; free local models keep the project at $0)
 * @param breakerWindow        how many recent calls per provider the circuit breaker looks at
 * @param breakerFailureRate   % of those that must fail to "open" the breaker (stop calling that provider)
 * @param breakerOpenFor       how long an open breaker waits before letting a test call through
 */
@ConfigurationProperties(prefix = "switchboard.failover")
public record FailoverProperties(
		int maxAttempts,
		Duration retryWait,
		List<String> fallbackModels,
		int breakerWindow,
		float breakerFailureRate,
		Duration breakerOpenFor) {

	public FailoverProperties {
		fallbackModels = fallbackModels == null ? List.of() : List.copyOf(fallbackModels);
		if (maxAttempts < 1) {
			throw new IllegalStateException("switchboard.failover.max-attempts must be at least 1");
		}
		if (retryWait == null || retryWait.isNegative()) {
			throw new IllegalStateException("switchboard.failover.retry-wait must not be negative");
		}
		if (breakerWindow < 1 || breakerFailureRate <= 0 || breakerFailureRate > 100 || breakerOpenFor == null) {
			throw new IllegalStateException("switchboard.failover circuit-breaker settings are invalid");
		}
	}
}
