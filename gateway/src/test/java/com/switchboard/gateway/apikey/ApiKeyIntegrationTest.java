package com.switchboard.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.switchboard.gateway.TestFixtures;
import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.user.Role;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;
import com.switchboard.gateway.user.UserStatus;

import reactor.core.publisher.Mono;

/** Member sb- keys and /v1 access, end to end with real Postgres. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyIntegrationTest {

	private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

	@Autowired
	private ApplicationContext context;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private LlmProvider provider;

	private WebTestClient client;
	private User member;
	private String memberToken;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).configureClient().build();
		when(provider.name()).thenReturn("ollama");
		when(provider.chat(any())).thenReturn(Mono.just(new ProviderResponse("llama3.2", "hello", "stop", 3, 1)));
		member = TestFixtures.createMember(users, passwordEncoder);
		memberToken = login(member.email(), TestFixtures.MEMBER_PASSWORD);
	}

	// ---------- helpers ----------

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	private WebTestClient.ResponseSpec createKey(String token, String name) {
		return client.post().uri("/me/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", name)).exchange();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> newKey(String token) {
		return createKey(token, "my key").expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
	}

	private WebTestClient.ResponseSpec listKeys(String token) {
		return client.get().uri("/me/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
	}

	private WebTestClient.ResponseSpec revoke(String token, Object id) {
		return client.delete().uri("/me/keys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange();
	}

	private WebTestClient.ResponseSpec chat(String authHeader) {
		var req = client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON);
		if (authHeader != null) {
			req = req.header(HttpHeaders.AUTHORIZATION, authHeader);
		}
		return req.bodyValue(CHAT_BODY).exchange();
	}

	private void setStatus(User u, UserStatus status) {
		users.save(TestFixtures.withStatus(u, status)).block();
	}

	// ---------- create / list ----------

	@Test
	void create_returnsFullKeyOnce_with201() {
		createKey(memberToken, "laptop").expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.id").isNotEmpty()
				.jsonPath("$.name").isEqualTo("laptop")
				.jsonPath("$.key").value(k -> assertThat((String) k).matches("sb-[A-Za-z0-9_-]{43}"))
				.jsonPath("$.key_prefix").value(p -> assertThat((String) p).startsWith("sb-").hasSize(11))
				.jsonPath("$.active").isEqualTo(true)
				.jsonPath("$.revoked_at").doesNotExist();
	}

	@Test
	void list_neverShowsFullKey() {
		String fullKey = (String) newKey(memberToken).get("key");

		String body = new String(listKeys(memberToken).expectStatus().isOk().expectBody()
				.jsonPath("$.length()").isEqualTo(1)
				.jsonPath("$[0].key").doesNotExist()
				.jsonPath("$[0].key_prefix").isEqualTo(fullKey.substring(0, 11))
				.returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
		assertThat(body).doesNotContain(fullKey);
	}

	@Test
	void database_storesOnlySha256Hash() {
		Map<String, Object> created = newKey(memberToken);
		String fullKey = (String) created.get("key");

		Map<String, Object> row = db.sql("SELECT * FROM api_keys WHERE id = :id")
				.bind("id", UUID.fromString((String) created.get("id"))).fetch().one().block();
		assertThat(row.get("key_hash")).isEqualTo(ApiKeyService.hash(fullKey));
		assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.equals(fullKey));
	}

	@Test
	void list_showsOnlyOwnKeys() {
		newKey(memberToken);
		User other = TestFixtures.createMember(users, passwordEncoder);
		String otherToken = login(other.email(), TestFixtures.MEMBER_PASSWORD);
		newKey(otherToken);
		newKey(otherToken);

		listKeys(memberToken).expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1);
		listKeys(otherToken).expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(2);
	}

	@Test
	void create_validation() {
		createKey(memberToken, "").expectStatus().isBadRequest();
		createKey(memberToken, "   ").expectStatus().isBadRequest();
		createKey(memberToken, "x".repeat(51)).expectStatus().isBadRequest();
		createKey(memberToken, "x".repeat(50)).expectStatus().isCreated();
	}

	@Test
	void create_limitOfTenActiveKeys() {
		String lastId = null;
		for (int i = 0; i < ApiKeyService.MAX_ACTIVE_KEYS_PER_USER; i++) {
			lastId = (String) newKey(memberToken).get("id");
		}
		createKey(memberToken, "eleventh").expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.error.code").isEqualTo("too_many_keys");

		revoke(memberToken, lastId).expectStatus().isNoContent();
		createKey(memberToken, "replacement").expectStatus().isCreated();
	}

	@Test
	void admin_canAlsoCreateKeys() {
		String adminToken = login("admin@test.local", "test-admin-password-123");
		String key = (String) newKey(adminToken).get("key");
		chat("Bearer " + key).expectStatus().isOk();
	}

	// ---------- using a key on /v1 ----------

	@Test
	void validKey_canCallChat() {
		String key = (String) newKey(memberToken).get("key");
		chat("Bearer " + key).expectStatus().isOk()
				.expectBody().jsonPath("$.choices[0].message.content").isEqualTo("hello");
	}

	@Test
	void usingKey_updatesLastUsedAt() {
		Map<String, Object> created = newKey(memberToken);
		chat("Bearer " + created.get("key")).expectStatus().isOk();

		await().atMost(Duration.ofSeconds(5)).until(() -> db.sql("SELECT last_used_at FROM api_keys WHERE id = :id")
				.bind("id", UUID.fromString((String) created.get("id"))).fetch().one().block()
				.get("last_used_at") != null);
	}

	@Test
	void requestLog_recordsMemberAndKey() {
		Map<String, Object> created = newKey(memberToken);
		chat("Bearer " + created.get("key")).expectStatus().isOk();

		await().atMost(Duration.ofSeconds(5)).until(() -> db.sql(
				"SELECT count(*) AS n FROM requests WHERE user_id = :u AND api_key_id = :k")
				.bind("u", member.id()).bind("k", UUID.fromString((String) created.get("id")))
				.fetch().one().block().get("n").equals(1L));
	}

	@Test
	void loginToken_canAlsoCallChat_forPlayground() {
		chat("Bearer " + memberToken).expectStatus().isOk();
		await().atMost(Duration.ofSeconds(5)).until(() -> db.sql(
				"SELECT count(*) AS n FROM requests WHERE user_id = :u AND api_key_id IS NULL")
				.bind("u", member.id()).fetch().one().block().get("n").equals(1L));
	}

	// ---------- rejected calls on /v1 ----------

	@Test
	void noAuthHeader_returns401() {
		chat(null).expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.error.type").isEqualTo("authentication_error")
				.jsonPath("$.error.code").isEqualTo("invalid_api_key")
				.jsonPath("$.error.message").value(m -> assertThat((String) m).contains("Bearer sb-"));
	}

	@Test
	void unknownOrMalformedKeys_return401() {
		chat("Bearer sb-" + "A".repeat(43)).expectStatus().isUnauthorized();
		chat("Bearer sb-short").expectStatus().isUnauthorized();
		chat("Bearer not-a-key-or-token").expectStatus().isUnauthorized();
		chat("Basic dXNlcjpwYXNz").expectStatus().isUnauthorized();
		chat("Bearer ").expectStatus().isUnauthorized();
	}

	@Test
	void revokedKey_stopsWorkingImmediately() {
		Map<String, Object> created = newKey(memberToken);
		String key = (String) created.get("key");
		chat("Bearer " + key).expectStatus().isOk();

		revoke(memberToken, created.get("id")).expectStatus().isNoContent();

		chat("Bearer " + key).expectStatus().isUnauthorized()
				.expectBody().jsonPath("$.error.code").isEqualTo("invalid_api_key");
		listKeys(memberToken).expectStatus().isOk().expectBody()
				.jsonPath("$[0].active").isEqualTo(false)
				.jsonPath("$[0].revoked_at").isNotEmpty();
	}

	@Test
	void disabledMember_keysStopWorking_andResumeWhenReenabled() {
		String key = (String) newKey(memberToken).get("key");
		chat("Bearer " + key).expectStatus().isOk();

		setStatus(member, UserStatus.DISABLED);
		chat("Bearer " + key).expectStatus().isUnauthorized();
		chat("Bearer " + memberToken).expectStatus().isForbidden()
				.expectBody().jsonPath("$.error.code").isEqualTo("account_disabled");

		setStatus(member, UserStatus.ACTIVE);
		chat("Bearer " + key).expectStatus().isOk();
	}

	@Test
	void deletedMember_keysAreRemovedAndStopWorking() {
		String key = (String) newKey(memberToken).get("key");
		users.deleteById(member.id()).block();

		chat("Bearer " + key).expectStatus().isUnauthorized();
		assertThat(db.sql("SELECT count(*) AS n FROM api_keys WHERE user_id = :u").bind("u", member.id())
				.fetch().one().block().get("n")).isEqualTo(0L);
	}

	// ---------- revoke rules ----------

	@Test
	void revoke_isIdempotent() {
		Object id = newKey(memberToken).get("id");
		revoke(memberToken, id).expectStatus().isNoContent();
		revoke(memberToken, id).expectStatus().isNoContent();
	}

	@Test
	void revoke_someoneElsesKey_returns404_andKeyKeepsWorking() {
		User other = TestFixtures.createMember(users, passwordEncoder);
		String otherToken = login(other.email(), TestFixtures.MEMBER_PASSWORD);
		Map<String, Object> othersKey = newKey(otherToken);

		revoke(memberToken, othersKey.get("id")).expectStatus().isNotFound();
		chat("Bearer " + othersKey.get("key")).expectStatus().isOk();
	}

	@Test
	void revoke_unknownOrInvalidId() {
		revoke(memberToken, UUID.randomUUID()).expectStatus().isNotFound();
		revoke(memberToken, "not-a-uuid").expectStatus().isBadRequest();
	}

	// ---------- who may manage keys ----------

	@Test
	void manageKeys_needsLoginToken() {
		client.get().uri("/me/keys").exchange().expectStatus().isUnauthorized();
		// An sb- key can call models but can't be used to manage keys.
		String key = (String) newKey(memberToken).get("key");
		listKeys(key).expectStatus().isUnauthorized();
		createKey(key, "sneaky").expectStatus().isUnauthorized();
	}

	@Test
	void disabledMember_cannotManageKeys() {
		setStatus(member, UserStatus.DISABLED);
		listKeys(memberToken).expectStatus().isForbidden();
		createKey(memberToken, "x").expectStatus().isForbidden();
	}

	@Test
	void member_cannotUseAdminEndpointsWithKey() {
		User admin = users.findByEmail("admin@test.local").block();
		assertThat(admin.role()).isEqualTo(Role.ADMIN);
		String key = (String) newKey(memberToken).get("key");
		client.get().uri("/admin/providers").header(HttpHeaders.AUTHORIZATION, "Bearer " + key).exchange()
				.expectStatus().isUnauthorized();
	}

	// ---------- logs ----------

	@Test
	void fullKey_neverWrittenToLogs(CapturedOutput output) {
		String key = (String) newKey(memberToken).get("key");
		chat("Bearer " + key).expectStatus().isOk();
		revoke(memberToken, listKeys(memberToken).expectBody(java.util.List.class).returnResult()
				.getResponseBody().stream().map(m -> ((Map<?, ?>) m).get("id")).findFirst().get())
				.expectStatus().isNoContent();

		assertThat(output.getAll()).doesNotContain(key).doesNotContain(key.substring(11))
				.contains("Created API key " + key.substring(0, 11));
	}
}
