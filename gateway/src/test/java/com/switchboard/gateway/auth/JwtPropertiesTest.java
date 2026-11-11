package com.switchboard.gateway.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

	private static final String GOOD_SECRET = "x".repeat(32);

	@Test
	void validSettings_areAccepted() {
		assertThatCode(() -> new JwtProperties(GOOD_SECRET, Duration.ofHours(8))).doesNotThrowAnyException();
	}

	@Test
	void missingSecret_isRejected() {
		assertThatThrownBy(() -> new JwtProperties(null, Duration.ofHours(1)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("SWITCHBOARD_JWT_SECRET");
		assertThatThrownBy(() -> new JwtProperties("", Duration.ofHours(1)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shortSecret_isRejected() {
		assertThatThrownBy(() -> new JwtProperties("x".repeat(31), Duration.ofHours(1)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
	}

	@Test
	void nonPositiveTtl_isRejected() {
		assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET, Duration.ZERO))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET, Duration.ofMinutes(-5)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtProperties(GOOD_SECRET, null))
				.isInstanceOf(IllegalStateException.class);
	}
}
