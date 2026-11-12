package com.switchboard.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderException.Kind;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.provider.ollama.OllamaProvider;

import reactor.core.publisher.Mono;

/** Retries and failover on real /v1 requests (FR-11). Fallback model in config: llama3.2. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class FailoverIntegrationTest {

	private static final String PASSWORD = "temporary-pass-123";

	@Autowired
	private ApplicationContext context;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private ReactiveStringRedisTemplate redis;

	@MockitoBean
	private OllamaProvider ollama;

	private WebTestClient client;
	private String adminToken;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).configureClient().build();
		redis.execute(c -> c.serverCommands().flushAll()).blockLast();
		when(ollama.name()).thenReturn("ollama");
		adminToken = login("admin@test.local", "test-admin-password-123");
	}

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	private String[] member(List<String> models) {
		Map<String, Object> body = new HashMap<>();
		String email = "fo-" + UUID.randomUUID() + "@example.com";
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("allowed_models", models);
		String id = client.post().uri("/admin/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("id")
				.toString();
		String key = client.post().uri("/me/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email, PASSWORD))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "k")).exchange()
				.expectBody(Map.class).returnResult().getResponseBody().get("key").toString();
		return new String[] { id, key };
	}

	private WebTestClient.ResponseSpec chat(String bearer, String model) {
		return client.post().uri("/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
				.exchange();
	}

	/** qwen fails with the given kind; llama3.2 answers. */
	private void qwenFails(Kind kind) {
		when(ollama.chat(any())).thenAnswer(inv -> {
			ProviderRequest r = inv.getArgument(0);
			return r.model().equals("qwen2.5:7b")
					? Mono.error(new ProviderException("ollama", "qwen failed (" + kind + ")", null, kind))
					: Mono.just(new ProviderResponse(r.model(), "answer from " + r.model(), "stop", 3, 4));
		});
	}

	private static org.mockito.ArgumentMatcher<ProviderRequest> model(String m) {
		return r -> r.model().equals(m);
	}

	@Test
	void failingModel_failsOverToLlama_andResponseSaysSo() {
		qwenFails(Kind.TRANSIENT);
		String[] m = member(List.of("qwen2.5:7b", "llama3.2"));

		chat(m[1], "qwen2.5:7b").expectStatus().isOk().expectBody()
				.jsonPath("$.model").isEqualTo("llama3.2")
				.jsonPath("$.choices[0].message.content").isEqualTo("answer from llama3.2")
				.jsonPath("$.switchboard.model_used").isEqualTo("llama3.2")
				.jsonPath("$.switchboard.fallback_from").isEqualTo("qwen2.5:7b");

		verify(ollama, times(2)).chat(argThat(model("qwen2.5:7b"))); // 1 try + 1 retry
		verify(ollama, times(1)).chat(argThat(model("llama3.2")));
		await().until(() -> db.sql("SELECT model, status FROM requests WHERE user_id = :u")
				.bind("u", UUID.fromString(m[0])).fetch().all().collectList().block().stream()
				.anyMatch(r -> "llama3.2".equals(r.get("model")) && "SUCCESS".equals(r.get("status"))));
	}

	@Test
	void fallbackNotAllowed_memberGets502_andLlamaNeverCalled() {
		qwenFails(Kind.TRANSIENT);
		String[] m = member(List.of("qwen2.5:7b"));

		chat(m[1], "qwen2.5:7b").expectStatus().isEqualTo(502).expectBody()
				.jsonPath("$.error.code").isEqualTo("ollama_unavailable");
		verify(ollama, never()).chat(argThat(model("llama3.2")));
		await().until(() -> db.sql("SELECT count(*) AS n FROM requests WHERE user_id = :u AND status = 'ERROR' "
				+ "AND model = 'qwen2.5:7b'").bind("u", UUID.fromString(m[0])).fetch().one().block()
				.get("n").equals(1L));
	}

	@Test
	void permanentError_noRetryNoFailover() {
		qwenFails(Kind.PERMANENT);
		String[] m = member(List.of("qwen2.5:7b", "llama3.2"));

		chat(m[1], "qwen2.5:7b").expectStatus().isEqualTo(502);
		verify(ollama, times(1)).chat(argThat(model("qwen2.5:7b")));
		verify(ollama, never()).chat(argThat(model("llama3.2")));
	}

	@Test
	void timeout_failsOverWithoutRetry() {
		qwenFails(Kind.TIMEOUT);
		chat(adminToken, "qwen2.5:7b").expectStatus().isOk().expectBody()
				.jsonPath("$.switchboard.fallback_from").isEqualTo("qwen2.5:7b");
		verify(ollama, times(1)).chat(argThat(model("qwen2.5:7b")));
	}

	@Test
	void flakyModel_recoversOnRetry_withoutFailover() {
		AtomicInteger calls = new AtomicInteger();
		when(ollama.chat(any())).thenAnswer(inv -> {
			ProviderRequest r = inv.getArgument(0);
			return calls.getAndIncrement() == 0
					? Mono.error(new ProviderException("ollama", "blip", null, Kind.TRANSIENT))
					: Mono.just(new ProviderResponse(r.model(), "ok", "stop", 1, 1));
		});

		chat(adminToken, "qwen2.5:7b").expectStatus().isOk().expectBody()
				.jsonPath("$.model").isEqualTo("qwen2.5:7b")
				.jsonPath("$.switchboard.fallback_from").doesNotExist();
		assertThat(calls).hasValue(2);
	}

	@Test
	void successfulCall_isUnchanged() {
		qwenFails(Kind.TRANSIENT);
		chat(adminToken, "llama3.2").expectStatus().isOk().expectBody()
				.jsonPath("$.model").isEqualTo("llama3.2")
				.jsonPath("$.switchboard.fallback_from").doesNotExist();
		verify(ollama, times(1)).chat(any());
	}
}
