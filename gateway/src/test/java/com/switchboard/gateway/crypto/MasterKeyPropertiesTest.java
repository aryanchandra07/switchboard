package com.switchboard.gateway.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class MasterKeyPropertiesTest {

	private static String base64OfLength(int bytes) {
		return Base64.getEncoder().encodeToString(new byte[bytes]);
	}

	@Test
	void valid32ByteKey_isAccepted() {
		assertThatCode(() -> new MasterKeyProperties(base64OfLength(32))).doesNotThrowAnyException();
		assertThat(new MasterKeyProperties(base64OfLength(32)).keyBytes()).hasSize(32);
	}

	@Test
	void surroundingWhitespace_isTolerated() {
		assertThatCode(() -> new MasterKeyProperties("  " + base64OfLength(32) + "\n")).doesNotThrowAnyException();
	}

	@Test
	void missingKey_isRejected() {
		assertThatThrownBy(() -> new MasterKeyProperties(null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("SWITCHBOARD_MASTER_KEY");
		assertThatThrownBy(() -> new MasterKeyProperties("   "))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void notBase64_isRejected() {
		assertThatThrownBy(() -> new MasterKeyProperties("this is not base64!!"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
	}

	@Test
	void wrongLength_isRejected() {
		assertThatThrownBy(() -> new MasterKeyProperties(base64OfLength(16)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("32").hasMessageContaining("16");
		assertThatThrownBy(() -> new MasterKeyProperties(base64OfLength(64)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void toString_hidesKey() {
		String key = base64OfLength(32);
		assertThat(new MasterKeyProperties(key).toString()).doesNotContain(key);
	}
}
