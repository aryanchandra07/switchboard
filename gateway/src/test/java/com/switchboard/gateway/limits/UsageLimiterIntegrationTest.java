package com.switchboard.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.switchboard.gateway.TestFixtures;
import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.requestlog.RequestLogRepository;
import com.switchboard.gateway.user.UserRepository;

import reactor.test.StepVerifier;

/** UsageLimiter against real Redis and Postgres (Testcontainers), with a controllable clock. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UsageLimiterIntegrationTest {

	private static final Instant NOON = Instant.parse("2026-03-15T12:00:10Z"); // 10 s into a minute

	@Autowired
	private ReactiveStringRedisTemplate redis;
	@Autowired
	private RequestLogRepository requestLogs;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder passwordEncoder;

	private UUID userId;

	@BeforeEach
	void setUp() {
		redis.execute(c -> c.serverCommands().flushAll()).blockLast();
		userId = TestFixtures.createMember(users, passwordEncoder).id();
	}

	private UsageLimiter at(Instant now) {
		return new UsageLimiter(redis, requestLogs, Clock.fixed(now, ZoneOffset.UTC));
	}

	private void insertCost(String usd, Instant when) {
		db.sql("INSERT INTO requests (user_id, status, cost_usd, created_at) VALUES (:u, 'SUCCESS', :c, :t)")
				.bind("u", userId).bind("c", new BigDecimal(usd)).bind("t", when)
				.fetch().rowsUpdated().block();
	}

	// ---------- rate limit ----------

	@Test
	void rate_allowsUpToLimit_thenRefusesWithRetryAfter() {
		UsageLimiter limiter = at(NOON);
		for (int i = 0; i < 3; i++) {
			StepVerifier.create(limiter.checkRate(userId, 3)).verifyComplete();
		}
		StepVerifier.create(limiter.checkRate(userId, 3))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(RequestBlockedException.RateLimited.class);
					var r = (RequestBlockedException.RateLimited) e;
					assertThat(r.limit()).isEqualTo(3);
					assertThat(r.retryAfterSeconds()).isEqualTo(50); // 10 s into the minute
					assertThat(r.reason()).isEqualTo("rate_limit_exceeded");
				})
				.verify();
	}

	@Test
	void rate_resetsInNextMinute() {
		StepVerifier.create(at(NOON).checkRate(userId, 1)).verifyComplete();
		StepVerifier.create(at(NOON).checkRate(userId, 1)).expectError().verify();
		StepVerifier.create(at(NOON.plusSeconds(60)).checkRate(userId, 1)).verifyComplete();
	}

	@Test
	void rate_isPerMember() {
		UUID other = TestFixtures.createMember(users, passwordEncoder).id();
		StepVerifier.create(at(NOON).checkRate(userId, 1)).verifyComplete();
		StepVerifier.create(at(NOON).checkRate(userId, 1)).expectError().verify();
		StepVerifier.create(at(NOON).checkRate(other, 1)).verifyComplete();
	}

	@Test
	void rate_keyExpiresOnItsOwn() {
		at(NOON).checkRate(userId, 5).block();
		String key = "sb:rl:" + userId + ":" + (NOON.getEpochSecond() / 60);
		Long ttl = redis.getExpire(key).map(d -> d.getSeconds()).block();
		assertThat(ttl).isBetween(60L, 70L);
	}

	// ---------- spend ----------

	@Test
	void spend_startsAtZero() {
		StepVerifier.create(at(NOON).currentSpend(userId))
				.assertNext(s -> {
					assertThat(s.daily()).isEqualByComparingTo("0");
					assertThat(s.monthly()).isEqualByComparingTo("0");
				})
				.verifyComplete();
	}

	@Test
	void spend_isRebuiltFromPostgres_dayAndMonthSeparately() {
		insertCost("0.40", NOON.minusSeconds(3600));                       // today
		insertCost("0.25", NOON.minusSeconds(60));                         // today
		insertCost("1.00", Instant.parse("2026-03-02T09:00:00Z"));          // earlier this month
		insertCost("5.00", Instant.parse("2026-02-27T09:00:00Z"));          // last month: ignored

		StepVerifier.create(at(NOON).currentSpend(userId))
				.assertNext(s -> {
					assertThat(s.daily()).isEqualByComparingTo("0.65");
					assertThat(s.monthly()).isEqualByComparingTo("1.65");
				})
				.verifyComplete();
		// Now cached in Redis.
		assertThat(redis.opsForValue().get(UsageLimiter.dayKey(userId, LocalDate.of(2026, 3, 15))).block())
				.isEqualTo("650000");
	}

	@Test
	void addSpend_incrementsBothCounters() {
		UsageLimiter limiter = at(NOON);
		limiter.currentSpend(userId).block(); // creates the counters
		limiter.addSpend(userId, new BigDecimal("0.123456")).block();
		limiter.addSpend(userId, new BigDecimal("0.000001")).block();

		StepVerifier.create(limiter.currentSpend(userId))
				.assertNext(s -> {
					assertThat(s.daily()).isEqualByComparingTo("0.123457");
					assertThat(s.monthly()).isEqualByComparingTo("0.123457");
				})
				.verifyComplete();
	}

	@Test
	void addSpend_beforeCountersExist_doesNotCreateWrongTotals() {
		// Without a counter, adding is skipped; the next read rebuilds from Postgres instead.
		insertCost("0.30", NOON.minusSeconds(10));
		at(NOON).addSpend(userId, new BigDecimal("0.30")).block();

		StepVerifier.create(at(NOON).currentSpend(userId))
				.assertNext(s -> assertThat(s.daily()).isEqualByComparingTo("0.30")) // not 0.60
				.verifyComplete();
	}

	@Test
	void addSpend_zeroOrNegative_isIgnored() {
		at(NOON).currentSpend(userId).block();
		at(NOON).addSpend(userId, BigDecimal.ZERO).block();
		at(NOON).addSpend(userId, new BigDecimal("-1")).block();
		at(NOON).addSpend(userId, null).block();
		StepVerifier.create(at(NOON).currentSpend(userId))
				.assertNext(s -> assertThat(s.daily()).isEqualByComparingTo("0")).verifyComplete();
	}

	@Test
	void newDay_startsFreshButMonthCarriesOver() {
		insertCost("0.50", NOON.minusSeconds(60));
		at(NOON).currentSpend(userId).block();

		Instant tomorrow = Instant.parse("2026-03-16T00:00:05Z");
		StepVerifier.create(at(tomorrow).currentSpend(userId))
				.assertNext(s -> {
					assertThat(s.daily()).isEqualByComparingTo("0");
					assertThat(s.monthly()).isEqualByComparingTo("0.50");
				})
				.verifyComplete();
	}

	@Test
	void newMonth_startsFresh() {
		insertCost("0.50", NOON.minusSeconds(60));
		Instant nextMonth = Instant.parse("2026-04-01T00:00:01Z");
		StepVerifier.create(at(nextMonth).currentSpend(userId))
				.assertNext(s -> assertThat(s.monthly()).isEqualByComparingTo("0"))
				.verifyComplete();
	}

	@Test
	void spend_isPerMember() {
		insertCost("0.50", NOON.minusSeconds(60));
		UUID other = TestFixtures.createMember(users, passwordEncoder).id();
		StepVerifier.create(at(NOON).currentSpend(other))
				.assertNext(s -> assertThat(s.daily()).isEqualByComparingTo("0")).verifyComplete();
	}

	@Test
	void micros_conversionIsExact() {
		assertThat(UsageLimiter.toMicros(new BigDecimal("1.234567"))).isEqualTo(1_234_567L);
		assertThat(UsageLimiter.toMicros(new BigDecimal("0.0000005"))).isEqualTo(1L); // rounds half up
		assertThat(UsageLimiter.fromMicros(1_234_567L)).isEqualByComparingTo("1.234567");
		assertThat(UsageLimiter.monthKey(userId, LocalDate.of(2026, 3, 5))).endsWith(":2026-03");
	}
}
