package com.switchboard.gateway.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.switchboard.gateway.TestcontainersConfiguration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Claude requests end to end: admin stores the key, member calls /v1, the gateway calls a fake Anthropic
 * API and the real cost is returned, logged and counted against the budget.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AnthropicIntegrationTest {

	private static final String PASSWORD = "temporary-pass-123";
	private static final String ANTHROPIC_KEY = "sk-ant-integration-test-key-123";
	private static final MockWebServer FAKE_ANTHROPIC = startServer();

	/** What the fake Anthropic answers with. Not a queue: a leftover request can't take another test's reply. */
	private static final java.util.concurrent.atomic.AtomicReference<MockResponse> NEXT_REPLY =
			new java.util.concurrent.atomic.AtomicReference<>();

	private static MockWebServer startServer() {
		try {
			MockWebServer s = new MockWebServer();
			s.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
				@Override
				public MockResponse dispatch(RecordedRequest request) {
					MockResponse r = NEXT_REPLY.get();
					return r != null ? r : new MockResponse().setResponseCode(500);
				}
			});
			s.start();
			return s;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void anthropicUrl(DynamicPropertyRegistry registry) {
		registry.add("switchboard.anthropic.enabled", () -> "true"); // off by default; these tests turn it on
		registry.add("switchboard.anthropic.base-url", () -> FAKE_ANTHROPIC.url("/").toString().replaceAll("/$", ""));
	}

	@AfterAll
	static void stop() throws IOException {
		FAKE_ANTHROPIC.shutdown();
	}

	@Autowired
	private ApplicationContext context;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private ReactiveStringRedisTemplate redis;

	private WebTestClient client;
	private String adminToken;

	@BeforeEach
	void setUp() throws InterruptedException {
		// The first Anthropic SDK call in a fresh JVM loads its HTTP/JSON libraries (~5 s), which is
		// exactly WebTestClient's default timeout, so give this class more room.
		client = WebTestClient.bindToApplicationContext(context).configureClient()
				.responseTimeout(java.time.Duration.ofSeconds(30)).build();
		redis.execute(c -> c.serverCommands().flushAll()).blockLast();
		db.sql("DELETE FROM provider_keys").fetch().rowsUpdated().block();
		NEXT_REPLY.set(null);
		while (FAKE_ANTHROPIC.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
			// drain requests left over from earlier tests
		}
		adminToken = login("admin@test.local", "test-admin-password-123");
	}

	// ---------- helpers ----------

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	private void storeAnthropicKey() {
		client.put().uri("/admin/providers/anthropic").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("api_key", ANTHROPIC_KEY)).exchange()
				.expectStatus().isOk();
	}

	private String[] member(Map<String, Object> limits) {
		Map<String, Object> body = new HashMap<>(limits);
		String email = "claude-" + UUID.randomUUID() + "@example.com";
		body.put("email", email);
		body.put("password", PASSWORD);
		String id = client.post().uri("/admin/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("id")
				.toString();
		String key = client.post().uri("/me/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "k")).exchange()
				.expectBody(Map.class).returnResult().getResponseBody().get("key").toString();
		return new String[] { id, key };
	}

	private static void anthropicReplies(int inputTokens, int outputTokens) {
		NEXT_REPLY.set(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
				{"id":"msg_01","type":"message","role":"assistant","model":"claude-haiku-5-5",
				 "content":[{"type":"text","text":"Hi from Claude"}],"stop_reason":"end_turn","stop_sequence":null,
				 "usage":{"input_tokens":%d,"output_tokens":%d}}""".formatted(inputTokens, outputTokens)));
	}

	private WebTestClient.ResponseSpec chat(String bearer, String body) {
		return client.post().uri("/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
	}

	private static String haiku(String extra) {
		return "{\"model\":\"claude-haiku-5-5\"," + extra
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"Hello Claude\"}]}";
	}

	// ---------- tests ----------

	@Test
	void member_callsClaude_getsRealCost_andItIsLogged() throws Exception {
		storeAnthropicKey();
		String[] m = member(Map.of("allowed_models", List.of("claude-haiku-5-5")));
		anthropicReplies(10_000, 2_000); // $0.001 + $0.001 = $0.002

		chat(m[1], haiku("")).expectStatus().isOk().expectBody()
				.jsonPath("$.model").isEqualTo("claude-haiku-5-5")
				.jsonPath("$.choices[0].message.content").isEqualTo("Hi from Claude")
				.jsonPath("$.usage.prompt_tokens").isEqualTo(10000)
				.jsonPath("$.usage.completion_tokens").isEqualTo(2000)
				.jsonPath("$.switchboard.provider").isEqualTo("anthropic")
				.jsonPath("$.switchboard.cost_usd").isEqualTo(0.002);

		RecordedRequest sent = FAKE_ANTHROPIC.takeRequest(2, TimeUnit.SECONDS);
		assertThat(sent.getHeader("Authorization")).contains(ANTHROPIC_KEY); // the admin's decrypted key
		assertThat(sent.getBody().readUtf8()).contains("Hello Claude");

		await().until(() -> db.sql("SELECT cost_usd FROM requests WHERE user_id = :u AND status = 'SUCCESS'")
				.bind("u", UUID.fromString(m[0])).fetch().all().collectList().block().stream()
				.anyMatch(r -> ((BigDecimal) r.get("cost_usd")).compareTo(new BigDecimal("0.002")) == 0));
	}

	@Test
	void realCost_countsAgainstBudget() {
		storeAnthropicKey();
		String[] m = member(Map.of("allowed_models", List.of("claude-haiku-5-5"),
				"daily_budget_usd", 0.01, "monthly_budget_usd", 1));
		anthropicReplies(100_000, 0); // exactly $0.01: uses up the daily budget

		chat(m[1], haiku("")).expectStatus().isOk();
		await().untilAsserted(() -> chat(m[1], haiku("")).expectStatus().isEqualTo(429).expectBody()
				.jsonPath("$.error.code").isEqualTo("daily_budget_exceeded")
				.jsonPath("$.error.details.daily_spent_usd").isEqualTo(0.01));
		assertThat(FAKE_ANTHROPIC.getRequestCount()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void maxTokens_isForwarded_andTooLargeIs400() throws Exception {
		storeAnthropicKey();
		anthropicReplies(1, 1);
		chat(adminToken, haiku("\"max_tokens\":300,")).expectStatus().isOk();
		assertThat(FAKE_ANTHROPIC.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8()).contains("\"max_tokens\":300");

		chat(adminToken, haiku("\"max_tokens\":20000,")).expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error.code").isEqualTo("invalid_model_request");
		chat(adminToken, haiku("\"max_tokens\":0,")).expectStatus().isBadRequest();
	}

	@Test
	void unpricedClaudeModel_is400_andNeverSent() {
		storeAnthropicKey();
		int before = FAKE_ANTHROPIC.getRequestCount();
		chat(adminToken, "{\"model\":\"claude-mystery-1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
				.expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error.message").value(v -> assertThat((String) v).contains("no price"));
		assertThat(FAKE_ANTHROPIC.getRequestCount()).isEqualTo(before);
	}

	@Test
	void noKeyStored_is502WithHint() {
		chat(adminToken, haiku("")).expectStatus().isEqualTo(502).expectBody()
				.jsonPath("$.error.code").isEqualTo("anthropic_unavailable")
				.jsonPath("$.error.message").value(v -> assertThat((String) v).contains("/admin/providers/anthropic"));
	}

	@Test
	void anthropicRejectsKey_is502_andKeyNotLeaked() {
		storeAnthropicKey();
		NEXT_REPLY.set(new MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
				.setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}"));
		String body = new String(chat(adminToken, haiku("")).expectStatus().isEqualTo(502).expectBody()
				.returnResult().getResponseBodyContent());
		assertThat(body).contains("rejected the stored API key").doesNotContain(ANTHROPIC_KEY);
	}

	@Test
	void adminModelList_includesPricedClaudeModels() {
		client.get().uri("/v1/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange()
				.expectStatus().isOk().expectBody()
				.jsonPath("$.data[?(@.id == 'claude-opus-5-5')].owned_by").isEqualTo("anthropic")
				.jsonPath("$.data[?(@.id == 'claude-haiku-5-5')]").exists();
	}
}
