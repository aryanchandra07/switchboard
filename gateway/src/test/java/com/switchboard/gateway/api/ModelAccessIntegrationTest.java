package com.switchboard.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Allowed models per member (FR-06) and GET /v1/models, end to end. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ModelAccessIntegrationTest {

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
		when(provider.chat(any())).thenAnswer(inv -> {
			ProviderRequest r = inv.getArgument(0);
			return Mono.just(new ProviderResponse(r.model(), "hi", "stop", 1, 1));
		});
		when(provider.listModels()).thenReturn(Flux.just("llama3.2", "qwen2.5:7b", "nomic-embed-text"));
		adminToken = login("admin@test.local", "test-admin-password-123");
	}

	// ---------- helpers ----------

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	/** Invites a member allowed to use {@code models}; returns [memberId, sb- key]. */
	private String[] member(List<String> models, Integer rpm) {
		Map<String, Object> body = new HashMap<>();
		String email = "models-" + UUID.randomUUID() + "@example.com";
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("allowed_models", models);
		if (rpm != null) {
			body.put("rpm_limit", rpm);
		}
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

	private WebTestClient.ResponseSpec chat(String bearer, String model) {
		String modelPart = model == null ? "" : "\"model\":\"" + model + "\",";
		return client.post().uri("/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{" + modelPart + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").exchange();
	}

	private WebTestClient.ResponseSpec models(String bearer) {
		var req = client.get().uri("/v1/models");
		if (bearer != null) {
			req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
		}
		return req.exchange();
	}

	private String modelSentToProvider() {
		ArgumentCaptor<ProviderRequest> c = ArgumentCaptor.forClass(ProviderRequest.class);
		verify(provider, org.mockito.Mockito.atLeastOnce()).chat(c.capture());
		return c.getValue().model();
	}

	// ---------- chat ----------

	@Test
	void allowedNamedModel_works() {
		String[] m = member(List.of("llama3.2", "qwen2.5:7b"), null);
		chat(m[1], "qwen2.5:7b").expectStatus().isOk().expectBody().jsonPath("$.model").isEqualTo("qwen2.5:7b");
		assertThat(modelSentToProvider()).isEqualTo("qwen2.5:7b");
	}

	@Test
	void notAllowedModel_returns403WithList_andModelNeverCalled() {
		String[] m = member(List.of("llama3.2"), null);
		clearInvocations(provider);

		chat(m[1], "claude-opus").expectStatus().isForbidden()
				.expectBody()
				.jsonPath("$.error.type").isEqualTo("permission_error")
				.jsonPath("$.error.code").isEqualTo("model_not_allowed")
				.jsonPath("$.error.message").value(v -> assertThat((String) v).contains("claude-opus"))
				.jsonPath("$.error.details.allowed_models[0]").isEqualTo("llama3.2");

		verify(provider, never()).chat(any());
		await().until(() -> (Long) db.sql("SELECT count(*) AS n FROM requests WHERE user_id = :u AND status = "
				+ "'BLOCKED' AND blocked_reason = 'model_not_allowed' AND model = 'claude-opus'")
				.bind("u", UUID.fromString(m[0])).fetch().one().block().get("n") == 1L);
	}

	@Test
	void auto_usesDefaultModelWhenAllowed() {
		String[] m = member(List.of("qwen2.5:7b", "llama3.2"), null);
		chat(m[1], "auto").expectStatus().isOk().expectBody().jsonPath("$.model").isEqualTo("llama3.2");
	}

	@Test
	void auto_fallsBackToFirstAllowedModel() {
		String[] m = member(List.of("qwen2.5:7b"), null);
		chat(m[1], null).expectStatus().isOk().expectBody().jsonPath("$.model").isEqualTo("qwen2.5:7b");
		assertThat(modelSentToProvider()).isEqualTo("qwen2.5:7b");
	}

	@Test
	void emptyAllowList_refusesEverything() {
		String[] m = member(List.of(), null);
		chat(m[1], "auto").expectStatus().isForbidden()
				.expectBody().jsonPath("$.error.message")
				.value(v -> assertThat((String) v).contains("not allowed to use any models"));
		chat(m[1], "llama3.2").expectStatus().isForbidden();
	}

	@Test
	void changingAllowList_takesEffectImmediately() {
		String[] m = member(List.of("llama3.2"), null);
		chat(m[1], "qwen2.5:7b").expectStatus().isForbidden();

		client.patch().uri("/admin/members/" + m[0]).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("allowed_models", List.of("llama3.2", "qwen2.5:7b"))).exchange()
				.expectStatus().isOk();
		chat(m[1], "qwen2.5:7b").expectStatus().isOk();

		client.patch().uri("/admin/members/" + m[0]).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("allowed_models", List.of())).exchange()
				.expectStatus().isOk();
		chat(m[1], "llama3.2").expectStatus().isForbidden();
	}

	@Test
	void refusedModel_doesNotUseUpRateLimit() {
		String[] m = member(List.of("llama3.2"), 1);
		chat(m[1], "claude-opus").expectStatus().isForbidden();
		chat(m[1], "claude-opus").expectStatus().isForbidden();
		chat(m[1], "llama3.2").expectStatus().isOk(); // still has its 1 request this minute
	}

	@Test
	void admin_canUseAnyModel() {
		chat(adminToken, "some-big-model").expectStatus().isOk(); // not on any member list
		assertThat(modelSentToProvider()).isEqualTo("some-big-model");
	}

	@Test
	void claudeModels_areSwitchedOffByDefault_with400() {
		// Even the admin, and even with a key stored: the default setup never contacts Anthropic.
		client.put().uri("/admin/providers/anthropic").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("api_key", "sk-ant-not-used-123456")).exchange()
				.expectStatus().isOk();
		chat(adminToken, "claude-haiku-5-5").expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error.code").isEqualTo("invalid_model_request")
				.jsonPath("$.error.message").value(v -> assertThat((String) v).contains("switched off"));

		String[] m = member(List.of("claude-haiku-5-5"), null); // allowed by the admin, but still off
		chat(m[1], "claude-haiku-5-5").expectStatus().isBadRequest();
		db.sql("DELETE FROM provider_keys").fetch().rowsUpdated().block();
	}

	// ---------- GET /v1/models ----------

	@Test
	void models_memberSeesOwnAllowList_inOpenAiFormat() {
		String[] m = member(List.of("llama3.2", "claude-haiku"), null);
		models(m[1]).expectStatus().isOk().expectBody()
				.jsonPath("$.object").isEqualTo("list")
				.jsonPath("$.data.length()").isEqualTo(2)
				.jsonPath("$.data[0].id").isEqualTo("llama3.2")
				.jsonPath("$.data[0].object").isEqualTo("model")
				.jsonPath("$.data[0].owned_by").isEqualTo("ollama")
				.jsonPath("$.data[1].id").isEqualTo("claude-haiku")
				.jsonPath("$.data[1].owned_by").isEqualTo("anthropic");
	}

	@Test
	void models_emptyAllowList_givesEmptyList() {
		String[] m = member(List.of(), null);
		models(m[1]).expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
	}

	@Test
	void models_adminSeesInstalledOllamaModels() {
		models(adminToken).expectStatus().isOk().expectBody()
				// 3 Ollama + mock (Claude is switched off by default, so none of its models show)
				.jsonPath("$.data.length()").isEqualTo(4)
				.jsonPath("$.data[1].id").isEqualTo("qwen2.5:7b")
				.jsonPath("$.data[3].id").isEqualTo("mock")
				.jsonPath("$.data[3].owned_by").isEqualTo("switchboard")
				.jsonPath("$.data[?(@.owned_by == 'anthropic')]").doesNotExist();
	}

	@Test
	void models_adminFallsBackToDefaultIfOllamaUnreachable() {
		when(provider.listModels()).thenReturn(Flux.error(new RuntimeException("connection refused")));
		models(adminToken).expectStatus().isOk().expectBody()
				// Ollama's default model stands in for the unreachable Ollama list
				.jsonPath("$.data.length()").isEqualTo(2)
				.jsonPath("$.data[0].id").isEqualTo("llama3.2")
				.jsonPath("$.data[1].id").isEqualTo("mock");
	}

	@Test
	void models_needsAuth_andWorksWithLoginTokenToo() {
		models(null).expectStatus().isUnauthorized();
		models("sb-" + "x".repeat(43)).expectStatus().isUnauthorized();

		Map<String, Object> body = new HashMap<>(Map.of("email", "m-" + UUID.randomUUID() + "@example.com",
				"password", PASSWORD, "allowed_models", List.of("llama3.2")));
		client.post().uri("/admin/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated();
		models(login((String) body.get("email"), PASSWORD)).expectStatus().isOk()
				.expectBody().jsonPath("$.data[0].id").isEqualTo("llama3.2");
	}

	@Test
	void models_doesNotUseUpRateLimit() {
		String[] m = member(List.of("llama3.2"), 1);
		for (int i = 0; i < 3; i++) {
			models(m[1]).expectStatus().isOk();
		}
		chat(m[1], null).expectStatus().isOk();
	}
}
