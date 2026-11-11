package com.switchboard.gateway.providerkey;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A row in "provider_keys". Holds only the encrypted key, never the plain one. */
@Table("provider_keys")
public record ProviderKey(
		@Id UUID id,
		String provider,
		String label,
		@Column("encrypted_key") byte[] encryptedKey,
		byte[] nonce,
		@Column("key_hint") String keyHint,
		@Column("created_at") Instant createdAt,
		@Column("rotated_at") Instant rotatedAt) {

	@Override
	public String toString() {
		// Even the ciphertext stays out of logs.
		return "ProviderKey[provider=" + provider + ", label=" + label + ", hint=..." + keyHint + "]";
	}
}
