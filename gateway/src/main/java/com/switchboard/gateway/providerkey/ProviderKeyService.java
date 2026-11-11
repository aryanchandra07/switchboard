package com.switchboard.gateway.providerkey;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.switchboard.gateway.crypto.SecretEncryptor;
import com.switchboard.gateway.crypto.SecretEncryptor.EncryptedSecret;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stores and rotates provider API keys (FR-04). Keys are encrypted before they reach the database
 * and are only ever decrypted inside the gateway, when calling the provider.
 */
@Service
public class ProviderKeyService {

	private static final Logger log = LoggerFactory.getLogger(ProviderKeyService.class);

	/** Providers that need an API key. (Ollama and Mock run locally and need none.) */
	public static final Set<String> SUPPORTED_PROVIDERS = Set.of("anthropic");

	private final ProviderKeyRepository repository;
	private final SecretEncryptor encryptor;
	private final Clock clock;

	@Autowired
	public ProviderKeyService(ProviderKeyRepository repository, SecretEncryptor encryptor) {
		this(repository, encryptor, Clock.systemUTC());
	}

	ProviderKeyService(ProviderKeyRepository repository, SecretEncryptor encryptor, Clock clock) {
		this.repository = repository;
		this.encryptor = encryptor;
		this.clock = clock;
	}

	/** Saves a new key, or replaces (rotates) the existing one for that provider. */
	public Mono<ProviderKey> save(String provider, String apiKey, String label) {
		String name = normalize(provider);
		if (!SUPPORTED_PROVIDERS.contains(name)) {
			return Mono.error(new UnknownProviderException(provider));
		}
		String key = apiKey.trim();
		EncryptedSecret encrypted = encryptor.encrypt(key, name);
		String hint = key.length() <= 4 ? key : key.substring(key.length() - 4);
		// Postgres stores microseconds; truncate so the response matches what is saved.
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

		return repository.findByProvider(name)
				.map(existing -> new ProviderKey(existing.id(), name, label, encrypted.ciphertext(),
						encrypted.nonce(), hint, existing.createdAt(), now))
				.defaultIfEmpty(new ProviderKey(null, name, label, encrypted.ciphertext(), encrypted.nonce(),
						hint, now, null))
				.flatMap(repository::save)
				.doOnNext(saved -> log.info("Stored {} key ending ...{} ({})", name, hint,
						saved.rotatedAt() == null ? "new" : "rotated"));
	}

	public Flux<ProviderKey> list() {
		return repository.findAllByOrderByProviderAsc();
	}

	/**
	 * Decrypted key for calling the provider. Internal use only: never return this to a client
	 * or write it to a log. Empty if the admin hasn't stored a key for this provider.
	 */
	public Mono<String> decryptedKey(String provider) {
		String name = normalize(provider);
		return repository.findByProvider(name)
				.map(k -> encryptor.decrypt(new EncryptedSecret(k.encryptedKey(), k.nonce()), name));
	}

	private static String normalize(String provider) {
		return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
	}

	public static class UnknownProviderException extends RuntimeException {
		public UnknownProviderException(String provider) {
			super("Unknown provider '" + provider + "'. Supported: " + String.join(", ", SUPPORTED_PROVIDERS));
		}
	}
}
