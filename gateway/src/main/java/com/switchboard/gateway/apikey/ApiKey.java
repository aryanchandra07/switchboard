package com.switchboard.gateway.apikey;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A row in "api_keys". Holds only the hash of the key, never the key itself. */
@Table("api_keys")
public record ApiKey(
		@Id UUID id,
		@Column("user_id") UUID userId,
		@Column("key_hash") String keyHash,
		@Column("key_prefix") String keyPrefix,
		String name,
		@Column("created_at") Instant createdAt,
		@Column("revoked_at") Instant revokedAt,
		@Column("last_used_at") Instant lastUsedAt) {

	public boolean isRevoked() {
		return revokedAt != null;
	}

	ApiKey revoke(Instant when) {
		return new ApiKey(id, userId, keyHash, keyPrefix, name, createdAt, when, lastUsedAt);
	}

	@Override
	public String toString() {
		return "ApiKey[id=" + id + ", prefix=" + keyPrefix + ", name=" + name + ", revoked=" + isRevoked() + "]";
	}
}
