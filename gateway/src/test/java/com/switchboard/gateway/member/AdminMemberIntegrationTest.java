package com.switchboard.gateway.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.provider.ollama.OllamaProvider;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.user.UserRepository;

import reactor.core.publisher.Mono;

/** Admin member management, end to end with real Postgres. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AdminMemberIntegrationTest {

	private static final String TEMP_PASSWORD = "temporary-pass-123";

	@Autowired
	private ApplicationContext context;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private UserRepository users;

	@MockitoBean // replaces the real Ollama provider; the Mock provider stays real
	private OllamaProvider provider;

	private WebTestClient client;
	private String adminToken;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).configureClient().build();
		when(provider.name()).thenReturn("ollama");
		when(provider.chat(any())).thenReturn(Mono.just(new ProviderResponse("llama3.2", "hi", "stop", 1, 1)));
		adminToken = login("admin@test.local", "test-admin-password-123");
	}

	// ---------- helpers ----------

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	private static String uniqueEmail() {
		return "invitee-" + UUID.randomUUID() + "@example.com";
	}

	private WebTestClient.ResponseSpec invite(Map<String, Object> body, String token) {
		return client.post().uri("/admin/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
	}

	private Map<String, Object> inviteBody(String email) {
		Map<String, Object> body = new HashMap<>();
		body.put("email", email);
		body.put("password", TEMP_PASSWORD);
		return body;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> inviteOk(Map<String, Object> body) {
		return invite(body, adminToken).expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
	}

	private WebTestClient.ResponseSpec patch(Object id, Object body, String token) {
		return client.patch().uri("/admin/members/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
	}

	private WebTestClient.ResponseSpec get(String path, String token) {
		return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
	}

	private WebTestClient.ResponseSpec delete(Object id, String token) {
		return client.delete().uri("/admin/members/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange();
	}

	private String newKey(String token) {
		return client.post().uri("/me/keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "k")).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("key")
				.toString();
	}

	private WebTestClient.ResponseSpec chat(String bearer) {
		return client.post().uri("/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").exchange();
	}

	// ---------- invite ----------

	@Test
	void invite_withDefaults_returns201AndMemberCanLogIn() {
		String email = uniqueEmail();
		invite(inviteBody(email), adminToken).expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.id").isNotEmpty()
				.jsonPath("$.email").isEqualTo(email)
				.jsonPath("$.status").isEqualTo("ACTIVE")
				.jsonPath("$.limits.daily_budget_usd").isEqualTo(1.0)
				.jsonPath("$.limits.monthly_budget_usd").isEqualTo(20.0)
				.jsonPath("$.limits.rpm_limit").isEqualTo(20)
				.jsonPath("$.limits.allowed_models[0]").isEqualTo("llama3.2")
				.jsonPath("$.password").doesNotExist()
				.jsonPath("$.password_hash").doesNotExist();

		String token = login(email, TEMP_PASSWORD);
		get("/auth/me", token).expectStatus().isOk().expectBody().jsonPath("$.role").isEqualTo("MEMBER");
	}

	@Test
	void invite_withCustomLimits() {
		Map<String, Object> body = inviteBody(uniqueEmail());
		body.put("daily_budget_usd", 2.5);
		body.put("monthly_budget_usd", 30);
		body.put("rpm_limit", 5);
		body.put("allowed_models", List.of("llama3.2", "claude-haiku"));

		invite(body, adminToken).expectStatus().isCreated().expectBody()
				.jsonPath("$.limits.daily_budget_usd").isEqualTo(2.5)
				.jsonPath("$.limits.monthly_budget_usd").isEqualTo(30)
				.jsonPath("$.limits.rpm_limit").isEqualTo(5)
				.jsonPath("$.limits.allowed_models.length()").isEqualTo(2)
				.jsonPath("$.limits.allowed_models[1]").isEqualTo("claude-haiku");
	}

	@Test
	void invite_emailIsNormalized_andDuplicateIs409() {
		String email = uniqueEmail();
		invite(inviteBody(email.toUpperCase()), adminToken).expectStatus().isCreated()
				.expectBody().jsonPath("$.email").isEqualTo(email);
		invite(inviteBody(email), adminToken).expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.error.code").isEqualTo("email_taken");
		invite(inviteBody("admin@test.local"), adminToken).expectStatus().isEqualTo(409);
	}

	@Test
	void invite_validation() {
		Map<String, Object> badEmail = inviteBody("not-an-email");
		invite(badEmail, adminToken).expectStatus().isBadRequest();

		Map<String, Object> shortPassword = inviteBody(uniqueEmail());
		shortPassword.put("password", "short");
		invite(shortPassword, adminToken).expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.message").value(m -> assertThat((String) m).contains("12"));

		Map<String, Object> badBudget = inviteBody(uniqueEmail());
		badBudget.put("daily_budget_usd", 50);
		badBudget.put("monthly_budget_usd", 10);
		invite(badBudget, adminToken).expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.message").value(m -> assertThat((String) m).contains("monthly"));

		Map<String, Object> badModel = inviteBody(uniqueEmail());
		badModel.put("allowed_models", List.of("has space"));
		invite(badModel, adminToken).expectStatus().isBadRequest();
	}

	@Test
	void invite_failedValidation_createsNoAccount() {
		String email = uniqueEmail();
		Map<String, Object> body = inviteBody(email);
		body.put("rpm_limit", 0);
		invite(body, adminToken).expectStatus().isBadRequest();
		assertThat(users.findByEmail(email).blockOptional()).isEmpty();
	}

	@Test
	void invite_passwordStoredAsBcryptHash_andNeverLogged(CapturedOutput output) {
		String email = uniqueEmail();
		inviteOk(inviteBody(email));

		String hash = (String) db.sql("SELECT password_hash FROM users WHERE email = :e").bind("e", email)
				.fetch().one().block().get("password_hash");
		assertThat(hash).startsWith("$2").doesNotContain(TEMP_PASSWORD);
		assertThat(output.getAll()).doesNotContain(TEMP_PASSWORD).contains("Invited member " + email);
	}

	// ---------- list / get ----------

	@Test
	void list_showsMembersOnly_notTheAdmin() {
		String email = uniqueEmail();
		inviteOk(inviteBody(email));

		String body = new String(get("/admin/members", adminToken).expectStatus().isOk().expectBody()
				.returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
		assertThat(body).contains(email).doesNotContain("admin@test.local").doesNotContain("password");
	}

	@Test
	void get_oneMember() {
		Map<String, Object> m = inviteOk(inviteBody(uniqueEmail()));
		get("/admin/members/" + m.get("id"), adminToken).expectStatus().isOk()
				.expectBody().jsonPath("$.email").isEqualTo(m.get("email"));
	}

	@Test
	void get_unknownOrAdminOrBadId() {
		get("/admin/members/" + UUID.randomUUID(), adminToken).expectStatus().isNotFound()
				.expectBody().jsonPath("$.error.code").isEqualTo("member_not_found");
		UUID adminId = users.findByEmail("admin@test.local").block().id();
		get("/admin/members/" + adminId, adminToken).expectStatus().isNotFound();
		get("/admin/members/not-a-uuid", adminToken).expectStatus().isBadRequest();
	}

	// ---------- update ----------

	@Test
	void patch_changesOnlyGivenFields() {
		Map<String, Object> m = inviteOk(inviteBody(uniqueEmail()));

		patch(m.get("id"), Map.of("rpm_limit", 99), adminToken).expectStatus().isOk().expectBody()
				.jsonPath("$.limits.rpm_limit").isEqualTo(99)
				.jsonPath("$.limits.daily_budget_usd").isEqualTo(1.0)
				.jsonPath("$.limits.allowed_models[0]").isEqualTo("llama3.2")
				.jsonPath("$.status").isEqualTo("ACTIVE");

		patch(m.get("id"), Map.of("allowed_models", List.of("qwen2.5:7b")), adminToken).expectStatus().isOk()
				.expectBody()
				.jsonPath("$.limits.rpm_limit").isEqualTo(99)
				.jsonPath("$.limits.allowed_models.length()").isEqualTo(1)
				.jsonPath("$.limits.allowed_models[0]").isEqualTo("qwen2.5:7b");

		// Persisted, not just echoed back.
		get("/admin/members/" + m.get("id"), adminToken).expectBody()
				.jsonPath("$.limits.rpm_limit").isEqualTo(99)
				.jsonPath("$.limits.allowed_models[0]").isEqualTo("qwen2.5:7b");
	}

	@Test
	void patch_newDailyIsCheckedAgainstExistingMonthly() {
		Map<String, Object> m = inviteOk(inviteBody(uniqueEmail())); // monthly 20
		patch(m.get("id"), Map.of("daily_budget_usd", 25), adminToken).expectStatus().isBadRequest();
		patch(m.get("id"), Map.of("daily_budget_usd", 25, "monthly_budget_usd", 100), adminToken)
				.expectStatus().isOk();
	}

	@Test
	void patch_invalidValues() {
		Object id = inviteOk(inviteBody(uniqueEmail())).get("id");
		patch(id, Map.of("rpm_limit", -5), adminToken).expectStatus().isBadRequest();
		patch(id, Map.of("daily_budget_usd", 1.234), adminToken).expectStatus().isBadRequest();
		patch(id, Map.of("status", "BANNED"), adminToken).expectStatus().isBadRequest();
		patch(UUID.randomUUID(), Map.of("rpm_limit", 5), adminToken).expectStatus().isNotFound();
	}

	@Test
	void disable_blocksLoginKeysAndChat_enableRestoresThem() {
		String email = uniqueEmail();
		Object id = inviteOk(inviteBody(email)).get("id");
		String token = login(email, TEMP_PASSWORD);
		String key = newKey(token);
		chat(key).expectStatus().isOk();

		patch(id, Map.of("status", "DISABLED"), adminToken).expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("DISABLED");

		chat(key).expectStatus().isUnauthorized();
		chat(token).expectStatus().isForbidden();
		get("/auth/me", token).expectStatus().isForbidden();
		client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", TEMP_PASSWORD)).exchange()
				.expectStatus().isForbidden();

		patch(id, Map.of("status", "ACTIVE"), adminToken).expectStatus().isOk();
		chat(key).expectStatus().isOk();
	}

	// ---------- delete ----------

	@Test
	void delete_removesAccountKeysAndLimits_butKeepsRequestLogs() {
		String email = uniqueEmail();
		Object id = inviteOk(inviteBody(email)).get("id");
		UUID uuid = UUID.fromString((String) id);
		String key = newKey(login(email, TEMP_PASSWORD));
		chat(key).expectStatus().isOk();
		org.awaitility.Awaitility.await().until(() -> count("requests", uuid) == 1);

		delete(id, adminToken).expectStatus().isNoContent();

		chat(key).expectStatus().isUnauthorized();
		get("/admin/members/" + id, adminToken).expectStatus().isNotFound();
		assertThat(count("api_keys", uuid)).isZero();
		assertThat(count("member_limits", uuid)).isZero();
		// The usage row survives with user_id cleared, so totals stay correct.
		assertThat(db.sql("SELECT count(*) AS n FROM requests WHERE user_id IS NULL AND api_key_id IS NULL")
				.fetch().one().block().get("n")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LONG)
				.isGreaterThanOrEqualTo(1L);
	}

	@Test
	void delete_unknownOrAdmin_returns404() {
		delete(UUID.randomUUID(), adminToken).expectStatus().isNotFound();
		UUID adminId = users.findByEmail("admin@test.local").block().id();
		delete(adminId, adminToken).expectStatus().isNotFound();
		login("admin@test.local", "test-admin-password-123"); // admin still fine
	}

	@Test
	void admin_cannotDisableThemselves() {
		UUID adminId = users.findByEmail("admin@test.local").block().id();
		patch(adminId, Map.of("status", "DISABLED"), adminToken).expectStatus().isNotFound();
		get("/auth/me", adminToken).expectStatus().isOk();
	}

	// ---------- access control ----------

	@Test
	void member_cannotUseAdminMemberEndpoints() {
		String email = uniqueEmail();
		Object id = inviteOk(inviteBody(email)).get("id");
		String memberToken = login(email, TEMP_PASSWORD);

		get("/admin/members", memberToken).expectStatus().isForbidden();
		invite(inviteBody(uniqueEmail()), memberToken).expectStatus().isForbidden();
		patch(id, Map.of("rpm_limit", 10000), memberToken).expectStatus().isForbidden();
		delete(id, memberToken).expectStatus().isForbidden();
	}

	@Test
	void noToken_returns401() {
		client.get().uri("/admin/members").exchange().expectStatus().isUnauthorized();
	}

	private long count(String table, UUID userId) {
		return (Long) db.sql("SELECT count(*) AS n FROM " + table + " WHERE user_id = :u").bind("u", userId)
				.fetch().one().block().get("n");
	}
}
