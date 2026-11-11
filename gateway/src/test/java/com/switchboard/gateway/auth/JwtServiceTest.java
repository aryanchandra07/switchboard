package com.switchboard.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.switchboard.gateway.user.Role;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserStatus;

import reactor.test.StepVerifier;

class JwtServiceTest {

	private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnop";
	private static final JwtProperties PROPS = new JwtProperties(SECRET, Duration.ofHours(8));
	private static final User USER = new User(UUID.randomUUID(), "alice@example.com", "hash", Role.MEMBER,
			UserStatus.ACTIVE, Instant.now(), Instant.now());

	private static SecretKeySpec key(String secret) {
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	private static JwtService service(String secret, Clock clock) {
		return new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(key(secret))), PROPS, clock);
	}

	private static NimbusReactiveJwtDecoder decoder(String secret) {
		var d = NimbusReactiveJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
		d.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtService.ISSUER));
		return d;
	}

	@Test
	void issue_containsUserIdEmailRoleIssuerAndExpiry() {
		Instant now = Instant.parse("2026-11-11T10:00:00Z");
		var token = service(SECRET, Clock.fixed(now, ZoneOffset.UTC)).issue(USER);

		assertThat(token.expiresInSeconds()).isEqualTo(8 * 3600);
		// Decode without time validation, to inspect the claims of a token "issued" at a fixed time.
		var lenient = NimbusReactiveJwtDecoder.withSecretKey(key(SECRET)).macAlgorithm(MacAlgorithm.HS256).build();
		lenient.setJwtValidator(jwt -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
		Jwt jwt = lenient.decode(token.value()).block();

		assertThat(jwt.getSubject()).isEqualTo(USER.id().toString());
		assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@example.com");
		assertThat(jwt.getClaimAsString("role")).isEqualTo("MEMBER");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("switchboard");
		assertThat(jwt.getIssuedAt()).isEqualTo(now);
		assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(8)));
		assertThat(jwt.getHeaders().get("alg")).hasToString("HS256");
	}

	@Test
	void freshToken_isAcceptedByDecoder() {
		var token = service(SECRET, Clock.systemUTC()).issue(USER);
		StepVerifier.create(decoder(SECRET).decode(token.value()))
				.assertNext(jwt -> assertThat(jwt.getSubject()).isEqualTo(USER.id().toString()))
				.verifyComplete();
	}

	@Test
	void expiredToken_isRejected() {
		Instant longAgo = Instant.now().minus(Duration.ofDays(2));
		var token = service(SECRET, Clock.fixed(longAgo, ZoneOffset.UTC)).issue(USER);
		StepVerifier.create(decoder(SECRET).decode(token.value())).expectError().verify();
	}

	@Test
	void tokenSignedWithDifferentSecret_isRejected() {
		var token = service("another-secret-0123456789abcdefghijklmnopq", Clock.systemUTC()).issue(USER);
		StepVerifier.create(decoder(SECRET).decode(token.value())).expectError().verify();
	}

	@Test
	void tamperedToken_isRejected() {
		String token = service(SECRET, Clock.systemUTC()).issue(USER).value();
		String[] parts = token.split("\\.");
		// Swap the payload for one claiming ADMIN; the signature no longer matches.
		String fakePayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"sub\":\"" + USER.id() + "\",\"role\":\"ADMIN\",\"iss\":\"switchboard\",\"exp\":9999999999}")
						.getBytes(StandardCharsets.UTF_8));
		String tampered = parts[0] + "." + fakePayload + "." + parts[2];
		StepVerifier.create(decoder(SECRET).decode(tampered)).expectError().verify();
	}

	@Test
	void eachIssueProducesAValidDistinctTokenOverTime() {
		var a = service(SECRET, Clock.fixed(Instant.now(), ZoneOffset.UTC)).issue(USER);
		var b = service(SECRET, Clock.fixed(Instant.now().plusSeconds(5), ZoneOffset.UTC)).issue(USER);
		assertThat(a.value()).isNotEqualTo(b.value());
	}
}
