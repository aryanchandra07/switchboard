package com.switchboard.gateway.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Encrypts secrets with AES-256-GCM (FR-04).
 *
 * <ul>
 * <li>Every encryption uses a fresh random 12-byte nonce ("number used once"), so encrypting the
 * same key twice gives different ciphertext. The nonce is stored next to the ciphertext; it isn't
 * secret, it just must never repeat.</li>
 * <li>GCM adds a 16-byte authentication tag: if anyone changes even one byte of the stored data,
 * decryption fails instead of returning garbage.</li>
 * <li>"Associated data" (here: a context string such as the provider name) is mixed into the tag,
 * so a ciphertext copied from one provider's row to another's won't decrypt.</li>
 * </ul>
 */
@Component
public class SecretEncryptor {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	public SecretEncryptor(MasterKeyProperties properties) {
		this.key = new SecretKeySpec(properties.keyBytes(), "AES");
	}

	public EncryptedSecret encrypt(String plaintext, String context) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return new EncryptedSecret(ciphertext, nonce);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encryption failed", e);
		}
	}

	/** @throws DecryptionException if the data was changed, the context differs, or the master key is wrong */
	public String decrypt(EncryptedSecret secret, String context) {
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, secret.nonce()));
			cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(secret.ciphertext()), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			// Deliberately vague: don't help an attacker learn why it failed.
			throw new DecryptionException();
		}
	}

	/** Ciphertext (includes the GCM tag) plus the nonce used to make it. */
	public record EncryptedSecret(byte[] ciphertext, byte[] nonce) {
	}

	public static class DecryptionException extends RuntimeException {
		public DecryptionException() {
			super("Stored secret could not be decrypted (wrong master key or data was modified)");
		}
	}
}
