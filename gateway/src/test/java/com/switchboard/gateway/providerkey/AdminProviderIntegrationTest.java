package com.switchboard.gateway.providerkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.reactive.server.WebTestClient;

import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.user.Role;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;

import reactor.test.StepVerifier;

/** Admin provider-key endpoints, end to end with real Postgres (Testcontainers). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AdminProviderIntegrationTest {

	private static final String KEY_1 = "sk-ant-api03-FIRSTkeyFIRSTkeyFIRSTkey-aaaa1111";
	private static final String KEY_2 = "sk-ant-api03-SECONDkeySECONDkeySECOND-bbbb2222";

	@Autowired
	private ApplicationContext context;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private ProviderKeyService service;
	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder passwordEncoder;

	private WebTestClient client;
	private String adminToken;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).configureClient().build();
		db.sql("DELETE FROM provider_keys").fetch().rowsUpdated().block();
		adminToken = login("admin@test.local", "test-admin-password-123");
	}

	/** Raw SQL reads of BYTEA columns come back as ByteBuffer; turn them into byte[]. */
	private static byte[] bytes(Object value) {
		java.nio.ByteBuffer buf = ((java.nio.ByteBuffer) value).duplicate();
		byte[] out = new byte[buf.remaining()];
		buf.get(out);
		return out;
	}

	private String login(String email, String password) {
		return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("access_token").toString();
	}

	private String memberToken() {
		String email = "member-" + UUID.randomUUID() + "@example.com";
		users.save(User.newUser(email, passwordEncoder.encode("member-password-1"), Role.MEMBER)).block();
		return login(email, "member-password-1");
	}

	private WebTestClient.ResponseSpec put(String provider, Object body, String token) {
		var req = client.put().uri("/admin/providers/" + provider).contentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return req.bodyValue(body).exchange();
	}

	private WebTestClient.ResponseSpec list(String token) {
		var req = client.get().uri("/admin/providers");
		if (token != null) {
			req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return req.exchange();
	}

	// ---------- saving ----------

	@Test
	void admin_savesKey_responseShowsOnlyHint() {
		put("anthropic", Map.of("api_key", KEY_1, "label", "Team key"), adminToken)
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.provider").isEqualTo("anthropic")
				.jsonPath("$.label").isEqualTo("Team key")
				.jsonPath("$.key_hint").isEqualTo("...1111")
				.jsonPath("$.created_at").isNotEmpty()
				.jsonPath("$.rotated_at").doesNotExist()
				.jsonPath("$.api_key").doesNotExist()
				.jsonPath("$.encrypted_key").doesNotExist();
	}

	@Test
	void savedKey_neverAppearsInAnyResponse() {
		String putBody = new String(put("anthropic", Map.of("api_key", KEY_1), adminToken).expectStatus().isOk()
				.expectBody().returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
		String listBody = new String(list(adminToken).expectStatus().isOk()
				.expectBody().returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);

		assertThat(putBody).doesNotContain(KEY_1).doesNotContain("FIRSTkey");
		assertThat(listBody).doesNotContain(KEY_1).doesNotContain("FIRSTkey");
	}

	@Test
	void database_holdsOnlyEncryptedBytes() {
		put("anthropic", Map.of("api_key", KEY_1), adminToken).expectStatus().isOk();

		Map<String, Object> row = db.sql("SELECT * FROM provider_keys WHERE provider = 'anthropic'")
				.fetch().one().block();
		byte[] stored = bytes(row.get("encrypted_key"));
		byte[] nonce = bytes(row.get("nonce"));

		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("sk-ant").doesNotContain("FIRST");
		assertThat(nonce).hasSize(12);
		assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.contains(KEY_1));
	}

	@Test
	void storedKey_decryptsBackToOriginalInsideGateway() {
		put("anthropic", Map.of("api_key", KEY_1), adminToken).expectStatus().isOk();
		StepVerifier.create(service.decryptedKey("anthropic")).expectNext(KEY_1).verifyComplete();
	}

	@Test
	void providerName_isCaseInsensitive_andKeyIsTrimmed() {
		put("Anthropic", Map.of("api_key", "  " + KEY_1 + "  "), adminToken).expectStatus().isBadRequest();
		// Surrounding spaces are rejected by validation; a clean key in mixed-case provider works:
		put("ANTHROPIC", Map.of("api_key", KEY_1), adminToken).expectStatus().isOk()
				.expectBody().jsonPath("$.provider").isEqualTo("anthropic");
		StepVerifier.create(service.decryptedKey("anthropic")).expectNext(KEY_1).verifyComplete();
	}

	// ---------- rotation ----------

	@Test
	void savingAgain_rotatesKey_keepsOneRowAndCreatedAt() {
		var first = put("anthropic", Map.of("api_key", KEY_1), adminToken).expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		byte[] firstCipher = bytes(db.sql("SELECT encrypted_key FROM provider_keys").fetch().one().block()
				.get("encrypted_key"));

		put("anthropic", Map.of("api_key", KEY_2, "label", "Rotated"), adminToken).expectStatus().isOk()
				.expectBody()
				.jsonPath("$.key_hint").isEqualTo("...2222")
				.jsonPath("$.label").isEqualTo("Rotated")
				.jsonPath("$.created_at").isEqualTo(first.get("created_at"))
				.jsonPath("$.rotated_at").isNotEmpty();

		assertThat(db.sql("SELECT count(*) AS n FROM provider_keys").fetch().one().block().get("n"))
				.isEqualTo(1L);
		byte[] secondCipher = bytes(db.sql("SELECT encrypted_key FROM provider_keys").fetch().one().block()
				.get("encrypted_key"));
		assertThat(secondCipher).isNotEqualTo(firstCipher);
		StepVerifier.create(service.decryptedKey("anthropic")).expectNext(KEY_2).verifyComplete();
	}

	// ---------- listing ----------

	@Test
	void list_emptyWhenNoKeys() {
		list(adminToken).expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
	}

	@Test
	void list_showsSavedProvider() {
		put("anthropic", Map.of("api_key", KEY_1, "label", "Team key"), adminToken).expectStatus().isOk();
		list(adminToken).expectStatus().isOk().expectBody()
				.jsonPath("$.length()").isEqualTo(1)
				.jsonPath("$[0].provider").isEqualTo("anthropic")
				.jsonPath("$[0].key_hint").isEqualTo("...1111")
				.jsonPath("$[0].label").isEqualTo("Team key");
	}

	@Test
	void decryptedKey_forProviderWithoutKey_isEmpty() {
		StepVerifier.create(service.decryptedKey("anthropic")).verifyComplete();
	}

	// ---------- validation ----------

	@Test
	void unknownProvider_returns400() {
		put("openai-nope", Map.of("api_key", KEY_1), adminToken).expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("unknown_provider")
				.jsonPath("$.error.message").value(m -> assertThat((String) m).contains("anthropic"));
	}

	@Test
	void blankOrMissingKey_returns400() {
		put("anthropic", Map.of("api_key", ""), adminToken).expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.code").isEqualTo("invalid_body");
		put("anthropic", Map.of("label", "no key"), adminToken).expectStatus().isBadRequest();
	}

	@Test
	void keyWithSpacesOrTooShortOrTooLong_returns400() {
		put("anthropic", Map.of("api_key", "sk ant with spaces"), adminToken).expectStatus().isBadRequest();
		put("anthropic", Map.of("api_key", "short"), adminToken).expectStatus().isBadRequest();
		put("anthropic", Map.of("api_key", "x".repeat(501)), adminToken).expectStatus().isBadRequest();
	}

	@Test
	void validationError_doesNotEchoTheKey() {
		String bad = "sk-ant SECRETVALUE";
		String body = new String(put("anthropic", Map.of("api_key", bad), adminToken).expectStatus().isBadRequest()
				.expectBody().returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("SECRETVALUE");
	}

	@Test
	void labelTooLong_returns400() {
		put("anthropic", Map.of("api_key", KEY_1, "label", "x".repeat(101)), adminToken)
				.expectStatus().isBadRequest();
	}

	// ---------- access control ----------

	@Test
	void member_cannotListOrSaveKeys() {
		String token = memberToken();
		list(token).expectStatus().isForbidden();
		put("anthropic", Map.of("api_key", KEY_1), token).expectStatus().isForbidden();
		StepVerifier.create(service.decryptedKey("anthropic")).verifyComplete(); // nothing was saved
	}

	@Test
	void noToken_returns401() {
		list(null).expectStatus().isUnauthorized();
		put("anthropic", Map.of("api_key", KEY_1), null).expectStatus().isUnauthorized();
	}

	// ---------- logs ----------

	@Test
	void key_neverWrittenToLogs(CapturedOutput output) {
		put("anthropic", Map.of("api_key", KEY_1), adminToken).expectStatus().isOk();
		put("anthropic", Map.of("api_key", KEY_2), adminToken).expectStatus().isOk();
		service.decryptedKey("anthropic").block();

		assertThat(output.getAll()).doesNotContain(KEY_1).doesNotContain(KEY_2)
				.doesNotContain("FIRSTkey").doesNotContain("SECONDkey")
				.contains("Stored anthropic key ending ...1111 (new)")
				.contains("Stored anthropic key ending ...2222 (rotated)");
	}
}
