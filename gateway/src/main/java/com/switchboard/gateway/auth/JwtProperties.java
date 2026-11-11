package com.switchboard.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login-token settings (prefix "switchboard.jwt").
 *
 * @param secret signing key; must be at least 32 bytes (256 bits) for HS256
 * @param ttl    how long a login token stays valid
 */
@ConfigurationProperties(prefix = "switchboard.jwt")
public record JwtProperties(String secret, Duration ttl) {

	static final int MIN_SECRET_BYTES = 32;

	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"SWITCHBOARD_JWT_SECRET must be set and at least " + MIN_SECRET_BYTES + " characters long");
		}
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalStateException("switchboard.jwt.ttl must be a positive duration");
		}
	}
}
