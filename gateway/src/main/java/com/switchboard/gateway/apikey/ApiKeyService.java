package com.switchboard.gateway.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.switchboard.gateway.apikey.ApiKeyRepository.UsableKey;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Members' sb- API keys (FR-05).
 *
 * <p>A key is "sb-" + 43 random characters (32 random bytes). Only its SHA-256 hash is stored.
 * A fast hash (not bcrypt) is fine here because the key is long and random, so it can't be
 * guessed, and it has to be checked on every request.
 */
@Service
public class ApiKeyService {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

	public static final String KEY_PREFIX = "sb-";
	static final int RANDOM_BYTES = 32;
	static final int DISPLAY_PREFIX_LENGTH = 11; // "sb-" + 8 characters
	public static final int MAX_ACTIVE_KEYS_PER_USER = 10;

	private final ApiKeyRepository repository;
	private final SecureRandom random = new SecureRandom();
	private final Clock clock;

	@Autowired
	public ApiKeyService(ApiKeyRepository repository) {
		this(repository, Clock.systemUTC());
	}

	ApiKeyService(ApiKeyRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** Creates a key. The returned {@code fullKey} must be shown to the member once and never stored. */
	public Mono<CreatedKey> create(UUID userId, String name) {
		return repository.countByUserIdAndRevokedAtIsNull(userId).flatMap(active -> {
			if (active >= MAX_ACTIVE_KEYS_PER_USER) {
				return Mono.error(new TooManyKeysException());
			}
			String fullKey = generateKey();
			ApiKey key = new ApiKey(null, userId, hash(fullKey), fullKey.substring(0, DISPLAY_PREFIX_LENGTH),
					name.trim(), now(), null, null);
			return repository.save(key)
					.doOnNext(saved -> log.info("Created API key {} ({}) for user {}", saved.keyPrefix(),
							saved.id(), userId))
					.map(saved -> new CreatedKey(saved, fullKey));
		});
	}

	public Flux<ApiKey> list(UUID userId) {
		return repository.findAllByUserIdOrderByCreatedAtDesc(userId);
	}

	/** Revokes one of the user's own keys. Empty if it doesn't exist or belongs to someone else. */
	public Mono<ApiKey> revoke(UUID userId, UUID keyId) {
		return repository.findByIdAndUserId(keyId, userId)
				.flatMap(k -> k.isRevoked() ? Mono.just(k) : repository.save(k.revoke(now())))
				.doOnNext(k -> log.info("Revoked API key {} ({})", k.keyPrefix(), k.id()));
	}

	/**
	 * Checks a key presented in a request. Empty if it's malformed, unknown, revoked, or its owner
	 * is disabled/removed. Also records when the key was last used (in the background).
	 */
	public Mono<UsableKey> verify(String presentedKey) {
		if (!looksLikeKey(presentedKey)) {
			return Mono.empty();
		}
		return repository.findUsableByHash(hash(presentedKey))
				.doOnNext(k -> repository.touchLastUsed(k.apiKeyId())
						.onErrorResume(e -> Mono.empty())
						.subscribe());
	}

	static boolean looksLikeKey(String value) {
		return value != null && value.startsWith(KEY_PREFIX) && value.length() >= 20 && value.length() <= 100;
	}

	String generateKey() {
		byte[] bytes = new byte[RANDOM_BYTES];
		random.nextBytes(bytes);
		return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	public record CreatedKey(ApiKey key, String fullKey) {
		@Override
		public String toString() {
			return "CreatedKey[" + key + "]"; // never print the full key
		}
	}

	public static class TooManyKeysException extends RuntimeException {
		public TooManyKeysException() {
			super("You already have " + MAX_ACTIVE_KEYS_PER_USER + " active keys. Revoke one before creating another.");
		}
	}
}
