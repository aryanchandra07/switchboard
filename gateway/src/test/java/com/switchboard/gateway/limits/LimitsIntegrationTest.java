package com.switchboard.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.provider.ollama.OllamaProvider;
import com.switchboard.gateway.provider.ProviderResponse;

import reactor.core.publisher.Mono;

/** Rate limits and budgets on real /v1 requests (real Postgres + Redis). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LimitsIntegrationTest {

	private static final String PASSWORD = "temporary-pass-123";

	@Autowired
	private ApplicationContext context;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private ReactiveStringRedisTemplate redis;

	@MockitoBean // replaces the real Ollama provider; the Mock provider stays real
	private OllamaProvider provider;

	private WebTestClient client;
	private String adminToken;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).configureClient().build();
		redis.execute(c -> c.serverCommands().flushAll()).blockLast();
		when(provider.name()).thenReturn("ollama");
		when(provider.chat(any())).thenReturn(Mono.just(new ProviderResponse("llama3.2", "hi", "stop", 1, 1)));
		adminToken = login("admin@test.local", "test-admin-password-123");
		avoidMinuteBoundary();
	}

	/**
	 * Rate-limit counters reset every clock minute. If a test starts in the last 15 seconds of a minute,
	 * wait for the next one, so a reset can't happen halfway through the test and make it flaky.
	 */
	private static void avoidMinuteBoundary() {
		long secondOfMinute = Instant.now().getEpochSecond() % 60;
		if (secondOfMinute >= 45) {
			await().atMost(java.time.Duration.ofSeconds(20))
					.until(() -> Instant.now().getEpochSecond() % 60 < 5);
		}
	}

	// ---------- helpers ----------

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	/** Invites a member with the given limits and returns [memberId, sb- key]. */
	private String[] memberWithKey(Map<String, Object> limits) {
		var body = new java.util.HashMap<String, Object>(limits);
		String email = "lim-" + UUID.randomUUID() + "@example.com";
		body.put("email", email);
		body.put("password", PASSWORD);
		String id = client.post().uri("/admin/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("id")
				.toString();
		String key = client.post().uri("/me/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "k")).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("key")
				.toString();
		return new String[] { id, key };
	}

	private WebTestClient.ResponseSpec chat(String bearer) {
		return client.post().uri("/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").exchange();
	}

	private void patchLimits(String id, Map<String, Object> body) {
		client.patch().uri("/admin/members/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
	}

	private void recordPastSpend(String userId, String usd) {
		db.sql("INSERT INTO requests (user_id, status, cost_usd, created_at) VALUES (:u, 'SUCCESS', :c, :t)")
				.bind("u", UUID.fromString(userId)).bind("c", new BigDecimal(usd)).bind("t", Instant.now())
				.fetch().rowsUpdated().block();
	}

	private long blockedRows(String userId, String reason) {
		return (Long) db.sql("SELECT count(*) AS n FROM requests WHERE user_id = :u AND status = 'BLOCKED' "
				+ "AND blocked_reason = :r").bind("u", UUID.fromString(userId)).bind("r", reason)
				.fetch().one().block().get("n");
	}

	// ---------- rate limit ----------

	@Test
	void overRateLimit_returns429WithRetryAfter_andIsLoggedAsBlocked() {
		String[] m = memberWithKey(Map.of("rpm_limit", 3));
		for (int i = 0; i < 3; i++) {
			chat(m[1]).expectStatus().isOk();
		}
		clearInvocations(provider);

		chat(m[1]).expectStatus().isEqualTo(429)
				.expectHeader().value(HttpHeaders.RETRY_AFTER, v -> assertThat(Integer.parseInt(v)).isBetween(1, 60))
				.expectBody()
				.jsonPath("$.error.type").isEqualTo("rate_limit_error")
				.jsonPath("$.error.code").isEqualTo("rate_limit_exceeded")
				.jsonPath("$.error.message").value(v -> assertThat((String) v).contains("3 requests per minute"))
				.jsonPath("$.error.details.rpm_limit").isEqualTo(3)
				.jsonPath("$.error.details.retry_after_seconds").isNumber();

		verify(provider, never()).chat(any()); // the model was never called
		await().until(() -> blockedRows(m[0], "rate_limit_exceeded") == 1);
	}

	@Test
	void rateLimit_isPerMember() {
		String[] a = memberWithKey(Map.of("rpm_limit", 1));
		String[] b = memberWithKey(Map.of("rpm_limit", 1));
		chat(a[1]).expectStatus().isOk();
		chat(a[1]).expectStatus().isEqualTo(429);
		chat(b[1]).expectStatus().isOk();
	}

	@Test
	void rateLimit_countsKeyAndLoginTokenTogether() {
		var body = new java.util.HashMap<String, Object>(Map.of("rpm_limit", 2));
		String email = "lim-" + UUID.randomUUID() + "@example.com";
		body.put("email", email);
		body.put("password", PASSWORD);
		client.post().uri("/admin/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated();
		String token = login(email, PASSWORD);
		String key = client.post().uri("/me/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "k")).exchange()
				.expectBody(Map.class).returnResult().getResponseBody().get("key").toString();

		chat(key).expectStatus().isOk();
		chat(token).expectStatus().isOk();
		chat(key).expectStatus().isEqualTo(429); // the limit is per member, not per key
	}

	@Test
	void raisingRateLimit_takesEffectImmediately() {
		String[] m = memberWithKey(Map.of("rpm_limit", 1));
		chat(m[1]).expectStatus().isOk();
		chat(m[1]).expectStatus().isEqualTo(429);
		patchLimits(m[0], Map.of("rpm_limit", 100));
		chat(m[1]).expectStatus().isOk();
	}

	@Test
	void admin_isNotRateLimited() {
		for (int i = 0; i < 25; i++) { // default member limit is 20
			chat(adminToken).expectStatus().isOk();
		}
	}

	// ---------- budgets ----------

	@Test
	void dailyBudgetUsedUp_returns429WithRemainingBudget() {
		String[] m = memberWithKey(Map.of("daily_budget_usd", 1, "monthly_budget_usd", 20));
		recordPastSpend(m[0], "1.00");
		clearInvocations(provider);

		chat(m[1]).expectStatus().isEqualTo(429)
				.expectBody()
				.jsonPath("$.error.type").isEqualTo("insufficient_quota")
				.jsonPath("$.error.code").isEqualTo("daily_budget_exceeded")
				.jsonPath("$.error.message").value(v -> assertThat((String) v).contains("$1.00").contains("UTC"))
				.jsonPath("$.error.details.daily_budget_usd").isEqualTo(1.0)
				.jsonPath("$.error.details.daily_spent_usd").isEqualTo(1.0)
				.jsonPath("$.error.details.daily_remaining_usd").isEqualTo(0.0)
				.jsonPath("$.error.details.monthly_remaining_usd").isEqualTo(19.0);

		verify(provider, never()).chat(any());
		await().until(() -> blockedRows(m[0], "daily_budget_exceeded") == 1);
	}

	@Test
	void monthlyBudgetUsedUp_isRefused() {
		String[] m = memberWithKey(Map.of("daily_budget_usd", 5, "monthly_budget_usd", 5));
		recordPastSpend(m[0], "4.00");
		chat(m[1]).expectStatus().isOk();
		recordPastSpend(m[0], "1.00");
		redis.execute(c -> c.serverCommands().flushAll()).blockLast(); // force a rebuild from Postgres

		// Daily and monthly are both $5 here; daily is checked first.
		chat(m[1]).expectStatus().isEqualTo(429).expectBody()
				.jsonPath("$.error.code").value(v -> assertThat((String) v).endsWith("_budget_exceeded"));
	}

	@Test
	void underBudget_isAllowed() {
		String[] m = memberWithKey(Map.of("daily_budget_usd", 1));
		recordPastSpend(m[0], "0.99");
		chat(m[1]).expectStatus().isOk();
	}

	@Test
	void raisingBudget_unblocksImmediately() {
		String[] m = memberWithKey(Map.of("daily_budget_usd", 1, "monthly_budget_usd", 20));
		recordPastSpend(m[0], "1.00");
		chat(m[1]).expectStatus().isEqualTo(429);
		patchLimits(m[0], Map.of("daily_budget_usd", 2));
		chat(m[1]).expectStatus().isOk();
	}

	@Test
	void zeroBudget_blocksAllRequests() {
		String[] m = memberWithKey(Map.of("daily_budget_usd", 0, "monthly_budget_usd", 0));
		chat(m[1]).expectStatus().isEqualTo(429);
	}

	@Test
	void blockedRequest_isLoggedWithMemberAndKey_butNoTokensOrCost() {
		String[] m = memberWithKey(Map.of("rpm_limit", 1));
		chat(m[1]).expectStatus().isOk();
		chat(m[1]).expectStatus().isEqualTo(429);

		await().until(() -> blockedRows(m[0], "rate_limit_exceeded") == 1);
		Map<String, Object> row = db.sql("SELECT * FROM requests WHERE user_id = :u AND status = 'BLOCKED'")
				.bind("u", UUID.fromString(m[0])).fetch().one().block();
		assertThat(row.get("api_key_id")).isNotNull();
		assertThat(row.get("tokens_in")).isEqualTo(0);
		assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0");
		assertThat(row.get("error_code")).isNull();
	}
}
