package com.switchboard.gateway.limits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.switchboard.gateway.requestlog.RequestLogRepository;

import reactor.core.publisher.Mono;

/**
 * Short-lived usage counters in Redis (FR-06, FR-07).
 *
 * <ul>
 * <li><b>Rate limit</b>: one counter per member per clock minute ("fixed window"). Each request adds 1;
 * the key expires on its own after the minute.</li>
 * <li><b>Spend</b>: today's and this month's spend per member, in micro-dollars (1 USD = 1,000,000) so the
 * maths is exact with whole numbers. If a counter is missing (first request of the day, or Redis was
 * restarted), it is rebuilt from the requests table in Postgres, which is the source of truth.</li>
 * </ul>
 * Days and months are in UTC.
 */
@Component
public class UsageLimiter {

	private static final Logger log = LoggerFactory.getLogger(UsageLimiter.class);

	/** INCR the minute counter; set its expiry when it's first created. Atomic inside Redis. */
	private static final RedisScript<Long> HIT = RedisScript.of("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
			return count
			""", Long.class);

	/** Add to a spend counter only if it already exists (otherwise it gets rebuilt from Postgres on next read). */
	private static final RedisScript<Long> ADD_IF_EXISTS = RedisScript.of("""
			if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('INCRBY', KEYS[1], ARGV[1]) end
			return -1
			""", Long.class);

	private static final Duration DAY_KEY_TTL = Duration.ofDays(2);
	private static final Duration MONTH_KEY_TTL = Duration.ofDays(32);

	private final ReactiveStringRedisTemplate redis;
	private final RequestLogRepository requestLogs;
	private final Clock clock;

	@Autowired
	public UsageLimiter(ReactiveStringRedisTemplate redis, RequestLogRepository requestLogs) {
		this(redis, requestLogs, Clock.systemUTC());
	}

	UsageLimiter(ReactiveStringRedisTemplate redis, RequestLogRepository requestLogs, Clock clock) {
		this.redis = redis;
		this.requestLogs = requestLogs;
		this.clock = clock;
	}

	// ---------- rate limit ----------

	/**
	 * Counts this request and fails with {@link RequestBlockedException.RateLimited} if the member is over
	 * their per-minute limit. If Redis is unreachable the request is allowed (logged as a warning), so a
	 * Redis outage doesn't take the whole gateway down.
	 */
	public Mono<Void> checkRate(UUID userId, int limitPerMinute) {
		Instant now = clock.instant();
		long minute = now.getEpochSecond() / 60;
		String key = "sb:rl:" + userId + ":" + minute;
		return redis.execute(HIT, List.of(key), List.of("70")).next()
				.onErrorResume(e -> {
					log.warn("Rate-limit check skipped, Redis unavailable: {}", e.toString());
					return Mono.just(0L);
				})
				.flatMap(count -> {
					if (count > limitPerMinute) {
						long retryAfter = 60 - (now.getEpochSecond() % 60);
						return Mono.error(new RequestBlockedException.RateLimited(limitPerMinute, retryAfter));
					}
					return Mono.empty();
				});
	}

	// ---------- spend ----------

	/** Today's and this month's spend. Falls back to Postgres if Redis is unavailable. */
	public Mono<Spend> currentSpend(UUID userId) {
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant monthStart = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();

		Mono<BigDecimal> daily = counter(dayKey(userId, today), DAY_KEY_TTL, userId, dayStart);
		Mono<BigDecimal> monthly = counter(monthKey(userId, today), MONTH_KEY_TTL, userId, monthStart);
		return Mono.zip(daily, monthly, Spend::new);
	}

	/** Adds a request's cost to today's and this month's counters. Errors are logged, never thrown. */
	public Mono<Void> addSpend(UUID userId, BigDecimal costUsd) {
		if (costUsd == null || costUsd.signum() <= 0) {
			return Mono.empty();
		}
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		String micros = String.valueOf(toMicros(costUsd));
		return redis.execute(ADD_IF_EXISTS, List.of(dayKey(userId, today)), List.of(micros)).next()
				.then(redis.execute(ADD_IF_EXISTS, List.of(monthKey(userId, today)), List.of(micros)).next())
				.then()
				.onErrorResume(e -> {
					log.warn("Could not update spend counters (will be rebuilt from Postgres): {}", e.toString());
					return Mono.empty();
				});
	}

	private Mono<BigDecimal> counter(String key, Duration ttl, UUID userId, Instant since) {
		Mono<BigDecimal> fromDatabase = requestLogs.sumCostSince(userId, since);
		return redis.opsForValue().get(key)
				.map(v -> fromMicros(Long.parseLong(v)))
				.switchIfEmpty(Mono.defer(() -> fromDatabase.flatMap(total -> redis.opsForValue()
						.setIfAbsent(key, String.valueOf(toMicros(total)), ttl)
						.thenReturn(total))))
				.onErrorResume(e -> {
					log.warn("Spend lookup fell back to Postgres, Redis unavailable: {}", e.toString());
					return fromDatabase;
				});
	}

	static String dayKey(UUID userId, LocalDate day) {
		return "sb:spend:d:" + userId + ":" + day;
	}

	static String monthKey(UUID userId, LocalDate day) {
		return "sb:spend:m:" + userId + ":" + day.getYear() + "-" + String.format("%02d", day.getMonthValue());
	}

	static long toMicros(BigDecimal usd) {
		return usd.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	static BigDecimal fromMicros(long micros) {
		return BigDecimal.valueOf(micros, 6);
	}
}
