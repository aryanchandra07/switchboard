package com.switchboard.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.user.Role;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;
import com.switchboard.gateway.user.UserStatus;

/**
 * End-to-end login tests: real HTTP layer, real Spring Security, real Postgres (Testcontainers).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthIntegrationTest {

	private static final String ADMIN_EMAIL = "admin@test.local"; // from application-test.properties
	private static final String ADMIN_PASSWORD = "test-admin-password-123";

	@Autowired
	private ApplicationContext context;
	@Autowired
	private UserRepository users;
	@Autowired
	private PasswordEncoder passwordEncoder;
	@Autowired
	private JwtEncoder jwtEncoder;
	@Autowired
	private JwtProperties jwtProperties;

	private WebTestClient client() {
		return WebTestClient.bindToApplicationContext(context)
				.apply(org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers
						.springSecurity())
				.configureClient().build();
	}

	private User createUser(String password, Role role, UserStatus status) {
		String email = "m-" + UUID.randomUUID() + "@example.com";
		User u = User.newUser(email, passwordEncoder.encode(password), role);
		u = new User(null, u.email(), u.passwordHash(), u.role(), status, u.createdAt(), u.updatedAt());
		return users.save(u).block();
	}

	private WebTestClient.ResponseSpec login(String email, String password) {
		return client().post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("email", email, "password", password)).exchange();
	}

	private String tokenFor(String email, String password) {
		return login(email, password).expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("access_token").toString();
	}

	private WebTestClient.ResponseSpec getWithToken(String path, String token) {
		return client().get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
	}

	// ---------- login ----------

	@Test
	void bootstrappedAdmin_canLogIn() {
		login(ADMIN_EMAIL, ADMIN_PASSWORD)
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.access_token").isNotEmpty()
				.jsonPath("$.token_type").isEqualTo("Bearer")
				.jsonPath("$.expires_in").isEqualTo(3600)
				.jsonPath("$.user.email").isEqualTo(ADMIN_EMAIL)
				.jsonPath("$.user.role").isEqualTo("ADMIN")
				.jsonPath("$.user.status").isEqualTo("ACTIVE")
				.jsonPath("$.user.password_hash").doesNotExist()
				.jsonPath("$.user.passwordHash").doesNotExist();
	}

	@Test
	void login_emailIsCaseInsensitive() {
		login("ADMIN@Test.Local", ADMIN_PASSWORD).expectStatus().isOk();
	}

	@Test
	void member_canLogIn() {
		User m = createUser("member-password-1", Role.MEMBER, UserStatus.ACTIVE);
		login(m.email(), "member-password-1").expectStatus().isOk()
				.expectBody().jsonPath("$.user.role").isEqualTo("MEMBER");
	}

	@Test
	void wrongPassword_returns401() {
		login(ADMIN_EMAIL, "wrong-password").expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.error.message").isEqualTo("Invalid email or password")
				.jsonPath("$.error.type").isEqualTo("authentication_error")
				.jsonPath("$.error.code").isEqualTo("invalid_credentials");
	}

	@Test
	void unknownEmail_returnsSame401AsWrongPassword() {
		login("nobody@example.com", "whatever-password").expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.error.message").isEqualTo("Invalid email or password")
				.jsonPath("$.error.code").isEqualTo("invalid_credentials");
	}

	@Test
	void disabledMember_cannotLogIn() {
		User m = createUser("member-password-2", Role.MEMBER, UserStatus.DISABLED);
		login(m.email(), "member-password-2").expectStatus().isForbidden()
				.expectBody().jsonPath("$.error.code").isEqualTo("account_disabled");
	}

	@Test
	void login_missingFields_returns400() {
		client().post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"email\":\"\"}").exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("invalid_body")
				.jsonPath("$.error.message").value(m -> assertThat((String) m).contains("email").contains("password"));
	}

	@Test
	void login_invalidJson_returns400() {
		client().post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{nope").exchange()
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.code").isEqualTo("invalid_json");
	}

	@Test
	void login_errorBody_neverEchoesThePassword() {
		var body = login(ADMIN_EMAIL, "super-secret-typo").expectStatus().isUnauthorized()
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(body).doesNotContain("super-secret-typo");
	}

	// ---------- using the token ----------

	@Test
	void me_withValidToken_returnsCurrentUser() {
		String token = tokenFor(ADMIN_EMAIL, ADMIN_PASSWORD);
		getWithToken("/auth/me", token).expectStatus().isOk()
				.expectBody()
				.jsonPath("$.email").isEqualTo(ADMIN_EMAIL)
				.jsonPath("$.role").isEqualTo("ADMIN")
				.jsonPath("$.passwordHash").doesNotExist();
	}

	@Test
	void me_withoutToken_returns401() {
		client().get().uri("/auth/me").exchange()
				.expectStatus().isUnauthorized()
				.expectBody()
				.jsonPath("$.error.type").isEqualTo("authentication_error")
				.jsonPath("$.error.code").isEqualTo("invalid_token");
	}

	@Test
	void me_withGarbageToken_returns401() {
		getWithToken("/auth/me", "not.a.real.token").expectStatus().isUnauthorized()
				.expectBody().jsonPath("$.error.code").isEqualTo("invalid_token");
	}

	@Test
	void me_withExpiredToken_returns401() {
		User admin = users.findByEmail(ADMIN_EMAIL).block();
		Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));
		String expired = new JwtService(jwtEncoder, jwtProperties, Clock.fixed(twoDaysAgo, ZoneOffset.UTC))
				.issue(admin).value();
		getWithToken("/auth/me", expired).expectStatus().isUnauthorized();
	}

	@Test
	void me_afterAccountDisabled_returns403EvenWithValidToken() {
		User m = createUser("member-password-3", Role.MEMBER, UserStatus.ACTIVE);
		String token = tokenFor(m.email(), "member-password-3");
		getWithToken("/auth/me", token).expectStatus().isOk();

		users.save(new User(m.id(), m.email(), m.passwordHash(), m.role(), UserStatus.DISABLED,
				m.createdAt(), Instant.now())).block();

		getWithToken("/auth/me", token).expectStatus().isForbidden()
				.expectBody().jsonPath("$.error.code").isEqualTo("account_disabled");
	}

	// ---------- role checks ----------

	@Test
	void adminPaths_memberGets403() {
		User m = createUser("member-password-4", Role.MEMBER, UserStatus.ACTIVE);
		String token = tokenFor(m.email(), "member-password-4");
		getWithToken("/admin/members", token).expectStatus().isForbidden()
				.expectBody().jsonPath("$.error.code").isEqualTo("forbidden");
	}

	@Test
	void adminPaths_adminPassesSecurity() {
		String token = tokenFor(ADMIN_EMAIL, ADMIN_PASSWORD);
		getWithToken("/admin/members", token).expectStatus().isOk();
		// A non-existent admin path gives 404 (not 401/403): security let the admin through.
		getWithToken("/admin/does-not-exist", token).expectStatus().isNotFound();
	}

	@Test
	void adminPaths_withoutToken_return401() {
		client().get().uri("/admin/members").exchange().expectStatus().isUnauthorized();
	}

	// ---------- public paths ----------

	@Test
	void health_isPublic() {
		client().get().uri("/actuator/health").exchange().expectStatus().isOk();
	}

	@Test
	void otherActuatorEndpoints_needLogin() {
		client().get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void unknownPath_withoutToken_returns401NotLeakingWhatExists() {
		client().get().uri("/does-not-exist").exchange().expectStatus().isUnauthorized();
	}
}
