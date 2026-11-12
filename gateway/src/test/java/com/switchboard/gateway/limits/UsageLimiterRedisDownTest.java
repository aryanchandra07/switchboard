package com.switchboard.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.switchboard.gateway.requestlog.RequestLogRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** What happens when Redis is unreachable. */
class UsageLimiterRedisDownTest {

	private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class, RETURNS_DEEP_STUBS);
	private final RequestLogRepository requestLogs = mock(RequestLogRepository.class);
	private final UsageLimiter limiter = new UsageLimiter(redis, requestLogs,
			Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC));
	private final UUID userId = UUID.randomUUID();

	@SuppressWarnings("unchecked")
	private void redisDown() {
		var down = new RedisConnectionFailureException("Connection refused");
		when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.error(down));
		when(redis.opsForValue().get(any())).thenReturn(Mono.error(down));
	}

	@Test
	void rateLimit_failsOpen_soRequestsStillWork() {
		redisDown();
		StepVerifier.create(limiter.checkRate(userId, 1)).verifyComplete();
		StepVerifier.create(limiter.checkRate(userId, 1)).verifyComplete();
	}

	@Test
	void spend_fallsBackToPostgres_soBudgetsStillApply() {
		redisDown();
		when(requestLogs.sumCostSince(any(), any())).thenReturn(Mono.just(new BigDecimal("3.50")));

		StepVerifier.create(limiter.currentSpend(userId))
				.assertNext(s -> {
					assertThat(s.daily()).isEqualByComparingTo("3.50");
					assertThat(s.monthly()).isEqualByComparingTo("3.50");
				})
				.verifyComplete();
	}

	@Test
	void addSpend_errorsAreSwallowed() {
		redisDown();
		StepVerifier.create(limiter.addSpend(userId, new BigDecimal("0.10"))).verifyComplete();
	}
}
