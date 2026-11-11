package com.switchboard.gateway.crypto;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The master key that encrypts provider API keys (prefix "switchboard.crypto").
 * It comes from the SWITCHBOARD_MASTER_KEY environment variable as base64 of exactly 32 random bytes
 * (AES-256). It is never stored in the database, so a stolen database alone reveals no provider keys.
 */
@ConfigurationProperties(prefix = "switchboard.crypto")
public record MasterKeyProperties(String masterKey) {

	static final int KEY_BYTES = 32;

	public MasterKeyProperties {
		if (masterKey == null || masterKey.isBlank()) {
			throw new IllegalStateException("SWITCHBOARD_MASTER_KEY must be set (base64 of 32 random bytes)");
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(masterKey.trim());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("SWITCHBOARD_MASTER_KEY is not valid base64");
		}
		if (decoded.length != KEY_BYTES) {
			throw new IllegalStateException("SWITCHBOARD_MASTER_KEY must decode to exactly " + KEY_BYTES
					+ " bytes, got " + decoded.length);
		}
	}

	byte[] keyBytes() {
		return Base64.getDecoder().decode(masterKey.trim());
	}

	@Override
	public String toString() {
		return "MasterKeyProperties[masterKey=****]"; // never print the key
	}
}
