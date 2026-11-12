package com.switchboard.gateway.resilience;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import reactor.core.publisher.Mono;

/**
 * Calls a provider with Resilience4j protection (FR-11):
 * <ul>
 * <li><b>Retry</b>: a TRANSIENT failure (connection refused, 5xx, 429) is tried again after a short,
 * doubling pause. Timeouts and PERMANENT failures are not retried.</li>
 * <li><b>Circuit breaker</b>, one per provider: if most recent calls to a provider failed, it "opens" and
 * calls fail instantly for a while instead of waiting on a provider that is down. Then a few test
 * calls are let through; if they work, it closes again.</li>
 * </ul>
 * Order: the breaker wraps each single attempt, and retry wraps the breaker, so every attempt counts
 * towards the breaker and an open breaker stops further retries.
 */
@Component
public class ResilientCaller {

	private static final Logger log = LoggerFactory.getLogger(ResilientCaller.class);

	private final RetryRegistry retries;
	private final CircuitBreakerRegistry breakers;

	public ResilientCaller(FailoverProperties props) {
		RetryConfig retryConfig = RetryConfig.custom()
				.maxAttempts(props.maxAttempts())
				.intervalFunction(props.retryWait().isZero() ? IntervalFunction.of(Duration.ofMillis(1))
						: IntervalFunction.ofExponentialBackoff(props.retryWait(), 2.0))
				.retryOnException(ResilientCaller::isRetryable)
				.build();
		CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(props.breakerWindow())
				.minimumNumberOfCalls(props.breakerWindow())
				.failureRateThreshold(props.breakerFailureRate())
				.waitDurationInOpenState(props.breakerOpenFor())
				.permittedNumberOfCallsInHalfOpenState(1)
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				// Only provider outages count; a bad request from a client says nothing about the provider's health.
				.recordException(ResilientCaller::countsAgainstProvider)
				.build();
		this.retries = RetryRegistry.of(retryConfig);
		this.breakers = CircuitBreakerRegistry.of(breakerConfig);
	}

	public Mono<ProviderResponse> call(LlmProvider provider, ProviderRequest request) {
		String name = provider.name();
		CircuitBreaker breaker = breakers.circuitBreaker(name);
		Retry retry = retries.retry(name);
		return Mono.defer(() -> provider.chat(request))
				.transformDeferred(CircuitBreakerOperator.of(breaker))
				.transformDeferred(RetryOperator.of(retry))
				.onErrorMap(CallNotPermittedException.class, e -> {
					log.warn("Skipping {}: circuit breaker is open after repeated failures", name);
					return new ProviderException(name, name + " is temporarily unavailable after repeated failures",
							e, ProviderException.Kind.TRANSIENT);
				});
	}

	/** Current breaker state per provider, e.g. "CLOSED" / "OPEN". For tests and metrics. */
	public CircuitBreaker.State breakerState(String providerName) {
		return breakers.circuitBreaker(providerName).getState();
	}

	static boolean isRetryable(Throwable e) {
		return e instanceof ProviderException pe && pe.kind() == ProviderException.Kind.TRANSIENT;
	}

	static boolean countsAgainstProvider(Throwable e) {
		return e instanceof ProviderException pe && pe.kind() != ProviderException.Kind.PERMANENT;
	}
}
