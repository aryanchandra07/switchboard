package com.switchboard.gateway.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.crypto.SecretEncryptor.DecryptionException;
import com.switchboard.gateway.crypto.SecretEncryptor.EncryptedSecret;

class SecretEncryptorTest {

	private static final String API_KEY = "sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

	private static SecretEncryptor encryptorWithRandomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return new SecretEncryptor(new MasterKeyProperties(Base64.getEncoder().encodeToString(key)));
	}

	private final SecretEncryptor encryptor = encryptorWithRandomKey();

	@Test
	void encryptThenDecrypt_returnsOriginal() {
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		assertThat(encryptor.decrypt(s, "anthropic")).isEqualTo(API_KEY);
	}

	@Test
	void unicodeAndEmpty_roundTrip() {
		assertThat(encryptor.decrypt(encryptor.encrypt("ключ-🔑-مفتاح", "x"), "x")).isEqualTo("ключ-🔑-مفتاح");
		assertThat(encryptor.decrypt(encryptor.encrypt("", "x"), "x")).isEmpty();
	}

	@Test
	void ciphertext_doesNotContainPlaintext() {
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		String asText = new String(s.ciphertext(), StandardCharsets.ISO_8859_1);
		assertThat(asText).doesNotContain("sk-ant").doesNotContain("ABCDEFGH");
	}

	@Test
	void ciphertext_includes16ByteAuthTag_andNonceIs12Bytes() {
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		assertThat(s.nonce()).hasSize(12);
		assertThat(s.ciphertext()).hasSize(API_KEY.getBytes(StandardCharsets.UTF_8).length + 16);
	}

	@Test
	void sameKeyEncryptedTwice_givesDifferentNonceAndCiphertext() {
		EncryptedSecret a = encryptor.encrypt(API_KEY, "anthropic");
		EncryptedSecret b = encryptor.encrypt(API_KEY, "anthropic");
		assertThat(a.nonce()).isNotEqualTo(b.nonce());
		assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
	}

	@Test
	void nonces_doNotRepeat() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			seen.add(Base64.getEncoder().encodeToString(encryptor.encrypt("k", "c").nonce()));
		}
		assertThat(seen).hasSize(1000);
	}

	@Test
	void tamperedCiphertext_isDetected() {
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		byte[] changed = s.ciphertext().clone();
		changed[0] ^= 1; // flip one bit
		assertThatThrownBy(() -> encryptor.decrypt(new EncryptedSecret(changed, s.nonce()), "anthropic"))
				.isInstanceOf(DecryptionException.class);
	}

	@Test
	void tamperedNonce_isDetected() {
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		byte[] changed = s.nonce().clone();
		changed[5] ^= 1;
		assertThatThrownBy(() -> encryptor.decrypt(new EncryptedSecret(s.ciphertext(), changed), "anthropic"))
				.isInstanceOf(DecryptionException.class);
	}

	@Test
	void differentContext_cannotDecrypt() {
		// A ciphertext moved from one provider's row to another's must not decrypt.
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		assertThatThrownBy(() -> encryptor.decrypt(s, "openai")).isInstanceOf(DecryptionException.class);
	}

	@Test
	void wrongMasterKey_cannotDecrypt() {
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		assertThatThrownBy(() -> encryptorWithRandomKey().decrypt(s, "anthropic"))
				.isInstanceOf(DecryptionException.class);
	}

	@Test
	void decryptionError_doesNotLeakTheSecret() {
		EncryptedSecret s = encryptor.encrypt(API_KEY, "anthropic");
		assertThatThrownBy(() -> encryptor.decrypt(s, "wrong"))
				.hasMessageNotContaining(API_KEY).hasNoCause();
	}

	@Test
	void garbageInput_isRejectedCleanly() {
		assertThatThrownBy(() -> encryptor.decrypt(new EncryptedSecret(new byte[3], new byte[12]), "x"))
				.isInstanceOf(DecryptionException.class);
		assertThatThrownBy(() -> encryptor.decrypt(new EncryptedSecret(new byte[40], new byte[0]), "x"))
				.isInstanceOf(DecryptionException.class);
	}
}
