package com.switchboard.gateway.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.List;
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

/** The real Mock provider behind the real HTTP stack; Ollama is replaced so we can prove it isn't called. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MockProviderIntegrationTest {

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
		adminToken = login("admin@test.local", "test-admin-password-123");
	}

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	private String[] memberAllowed(List<String> models) {
		Map<String, Object> body = new HashMap<>();
		String email = "mock-" + UUID.randomUUID() + "@example.com";
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
				.bodyValue("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello!\"}]}")
				.exchange();
	}

	@Test
	void mockModel_isAnsweredByMock_notOllama() {
		chat(adminToken, "mock").expectStatus().isOk().expectBody()
				.jsonPath("$.model").isEqualTo("mock")
				.jsonPath("$.choices[0].message.content").isEqualTo("This is a mock reply from Switchboard.")
				.jsonPath("$.usage.prompt_tokens").isEqualTo(2)
				.jsonPath("$.usage.completion_tokens").isEqualTo(8)
				.jsonPath("$.switchboard.provider").isEqualTo("mock")
				.jsonPath("$.switchboard.cost_usd").isEqualTo(0);
		verify(ollama, never()).chat(any());
	}

	@Test
	void mockVariant_alsoGoesToMock() {
		chat(adminToken, "mock-fast").expectStatus().isOk().expectBody()
				.jsonPath("$.model").isEqualTo("mock-fast")
				.jsonPath("$.switchboard.provider").isEqualTo("mock");
	}

	@Test
	void member_needsMockOnAllowList() {
		String[] notAllowed = memberAllowed(List.of("llama3.2"));
		chat(notAllowed[1], "mock").expectStatus().isForbidden();

		String[] allowed = memberAllowed(List.of("mock"));
		chat(allowed[1], "mock").expectStatus().isOk();
		verify(ollama, never()).chat(any());
	}

	@Test
	void mockRequests_areLoggedWithProviderMock() {
		String[] m = memberAllowed(List.of("mock"));
		chat(m[1], "mock").expectStatus().isOk();
		await().until(() -> db.sql("SELECT provider, model, status, tokens_out FROM requests WHERE user_id = :u")
				.bind("u", UUID.fromString(m[0])).fetch().all().collectList().block().stream()
				.anyMatch(r -> "mock".equals(r.get("provider")) && "SUCCESS".equals(r.get("status"))
						&& Integer.valueOf(8).equals(r.get("tokens_out"))));
	}

	@Test
	void autoForMockOnlyMember_usesMock() {
		String[] m = memberAllowed(List.of("mock"));
		client.post().uri("/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + m[1])
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.switchboard.provider").isEqualTo("mock");
	}

	@Test
	void manyMockRequests_areFast() {
		long start = System.nanoTime();
		for (int i = 0; i < 200; i++) {
			chat(adminToken, "mock").expectStatus().isOk();
		}
		long ms = (System.nanoTime() - start) / 1_000_000;
		// A rough smoke check only; real throughput is measured with k6 in Week 7.
		assertThat(ms).as("200 sequential mock requests took %d ms", ms).isLessThan(20_000);
	}
}
