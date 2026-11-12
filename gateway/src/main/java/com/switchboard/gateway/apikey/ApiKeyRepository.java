package com.switchboard.gateway.apikey;

import java.util.UUID;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKey, UUID> {

	Flux<ApiKey> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

	Mono<ApiKey> findByIdAndUserId(UUID id, UUID userId);

	Mono<Long> countByUserIdAndRevokedAtIsNull(UUID userId);

	/**
	 * Looks up a key by hash and returns it only if it can be used right now:
	 * not revoked, and its owner exists and is ACTIVE. One query per request.
	 */
	@Query("""
			SELECT k.id AS api_key_id, k.user_id, u.role
			FROM api_keys k
			JOIN users u ON u.id = k.user_id
			WHERE k.key_hash = :hash
			  AND k.revoked_at IS NULL
			  AND u.status = 'ACTIVE'
			""")
	Mono<UsableKey> findUsableByHash(String hash);

	/** Records use, at most once a minute per key, so busy keys don't cause a write on every request. */
	@Modifying
	@Query("""
			UPDATE api_keys SET last_used_at = now()
			WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - INTERVAL '1 minute')
			""")
	Mono<Long> touchLastUsed(UUID id);

	/** Result of {@link #findUsableByHash}. */
	record UsableKey(UUID apiKeyId, UUID userId, String role) {
	}
}
