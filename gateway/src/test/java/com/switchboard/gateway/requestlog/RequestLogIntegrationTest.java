package com.switchboard.gateway.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.switchboard.gateway.TestFixtures;
import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.apikey.ApiKeyService;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;
import com.switchboard.gateway.provider.ollama.OllamaProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderResponse;

import reactor.core.publisher.Mono;

/** Chat requests end to end, checking the rows written to the real "requests" table. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RequestLogIntegrationTest {

	private static final String SECRET_PROMPT = "my-private-prompt-text-xyz";
	private static final String SECRET_REPLY = "the-model-private-reply-abc";

	@Autowired
	private ApplicationContext context;
	@Autowired
	private DatabaseClient db;

	@MockitoBean // replaces the real Ollama provider; the Mock provider stays real
	private OllamaProvider provider;

	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder passwordEncoder;
	@Autowired
	private ApiKeyService apiKeys;
	@Autowired
	private com.switchboard.gateway.member.MemberService memberService;

	private WebTestClient client;
	private User member;
	private ApiKeyService.CreatedKey key;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).configureClient().build();
		db.sql("DELETE FROM requests").fetch().rowsUpdated().block();
		member = TestFixtures.createMember(users, passwordEncoder);
		TestFixtures.allowModels(memberService, member.id());
		key = TestFixtures.createKey(apiKeys, member);
		when(provider.name()).thenReturn("ollama");
		when(provider.chat(any())).thenReturn(
				Mono.just(new ProviderResponse("llama3.2", SECRET_REPLY, "stop", 12, 34)));
	}

	private WebTestClient.ResponseSpec chat(String body) {
		return client.post().uri("/v1/chat/completions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + key.fullKey()).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange();
	}

	private List<Map<String, Object>> rows() {
		return db.sql("SELECT * FROM requests ORDER BY created_at").fetch().all().collectList().block();
	}

	/** Logging happens in the background, so wait (up to 5 s) for the expected number of rows. */
	private List<Map<String, Object>> awaitRows(int count) {
		await().atMost(Duration.ofSeconds(5)).until(() -> rows().size() >= count);
		return rows();
	}

	@Test
	void successfulRequest_writesSuccessRow() {
		chat("{\"model\":\"auto\",\"messages\":[{\"role\":\"user\",\"content\":\"" + SECRET_PROMPT + "\"}]}")
				.expectStatus().isOk();

		Map<String, Object> row = awaitRows(1).get(0);
		assertThat(row.get("id")).isNotNull();
		assertThat(row.get("status")).isEqualTo("SUCCESS");
		assertThat(row.get("provider")).isEqualTo("ollama");
		assertThat(row.get("model")).isEqualTo("llama3.2");
		assertThat(row.get("tokens_in")).isEqualTo(12);
		assertThat(row.get("tokens_out")).isEqualTo(34);
		assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0");
		assertThat((Integer) row.get("latency_ms")).isGreaterThanOrEqualTo(0);
		assertThat(row.get("cache_hit")).isEqualTo(false);
		assertThat(row.get("error_code")).isNull();
		assertThat(row.get("user_id")).isEqualTo(member.id());
		assertThat(row.get("api_key_id")).isEqualTo(key.key().id());
		assertThat(row.get("created_at")).isNotNull();
	}

	@Test
	void providerFailure_writesErrorRow() {
		when(provider.chat(any())).thenReturn(Mono.error(new ProviderException("ollama", "down", null)));

		chat("{\"model\":\"qwen2.5:7b\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
				.expectStatus().isEqualTo(502);

		Map<String, Object> row = awaitRows(1).get(0);
		assertThat(row.get("status")).isEqualTo("ERROR");
		assertThat(row.get("error_code")).isEqualTo("ollama_unavailable");
		assertThat(row.get("model")).isEqualTo("qwen2.5:7b");
		assertThat(row.get("tokens_in")).isEqualTo(0);
	}

	@Test
	void invalidRequest_isNotLogged() {
		// Rejected before reaching the pipeline: no model was called, so nothing to record.
		chat("{\"messages\":[]}").expectStatus().isBadRequest();
		chat("{nope").expectStatus().isBadRequest();

		// Then a valid one, so we know logging works and the earlier ones really were skipped.
		chat("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").expectStatus().isOk();
		assertThat(awaitRows(1)).hasSize(1);
	}

	@Test
	void severalRequests_eachGetTheirOwnRow() {
		for (int i = 0; i < 5; i++) {
			chat("{\"messages\":[{\"role\":\"user\",\"content\":\"hi " + i + "\"}]}").expectStatus().isOk();
		}
		List<Map<String, Object>> rows = awaitRows(5);
		assertThat(rows).hasSize(5);
		assertThat(rows.stream().map(r -> r.get("id")).distinct()).hasSize(5);
	}

	@Test
	void promptAndReplyText_neverStored() {
		chat("{\"messages\":[{\"role\":\"user\",\"content\":\"" + SECRET_PROMPT + "\"}]}").expectStatus().isOk();

		Map<String, Object> row = awaitRows(1).get(0);
		assertThat(row.values().stream().map(String::valueOf))
				.noneMatch(v -> v.contains(key.fullKey()));
		assertThat(row.values().stream().map(String::valueOf))
				.noneMatch(v -> v.contains(SECRET_PROMPT) || v.contains(SECRET_REPLY));
	}

	@Test
	void databaseRejectsInvalidRows() {
		var bad = db.sql("INSERT INTO requests (status) VALUES ('MAYBE')").fetch().rowsUpdated();
		reactor.test.StepVerifier.create(bad).expectError().verify();
		var negative = db.sql("INSERT INTO requests (status, tokens_in) VALUES ('SUCCESS', -1)").fetch().rowsUpdated();
		reactor.test.StepVerifier.create(negative).expectError().verify();
	}
}
