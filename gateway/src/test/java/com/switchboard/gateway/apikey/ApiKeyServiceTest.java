package com.switchboard.gateway.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.switchboard.gateway.apikey.ApiKeyRepository.UsableKey;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApiKeyServiceTest {

	private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
	private final ApiKeyService service = new ApiKeyService(repository);
	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		when(repository.countByUserIdAndRevokedAtIsNull(any())).thenReturn(Mono.just(0L));
		when(repository.save(any())).thenAnswer(inv -> {
			ApiKey k = inv.getArgument(0);
			return Mono.just(new ApiKey(k.id() == null ? UUID.randomUUID() : k.id(), k.userId(), k.keyHash(),
					k.keyPrefix(), k.name(), k.createdAt(), k.revokedAt(), k.lastUsedAt()));
		});
		when(repository.touchLastUsed(any())).thenReturn(Mono.just(1L));
	}

	// ---------- key format and hashing ----------

	@Test
	void generatedKey_hasSbPrefixAnd43UrlSafeCharacters() {
		String key = service.generateKey();
		assertThat(key).startsWith("sb-").hasSize(3 + 43).matches("sb-[A-Za-z0-9_-]{43}");
	}

	@Test
	void generatedKeys_areUnique() {
		Set<String> keys = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			keys.add(service.generateKey());
		}
		assertThat(keys).hasSize(1000);
	}

	@Test
	void hash_isDeterministic64HexAndNotTheKey() {
		String key = service.generateKey();
		assertThat(ApiKeyService.hash(key)).isEqualTo(ApiKeyService.hash(key))
				.hasSize(64).matches("[0-9a-f]{64}").doesNotContain(key.substring(3));
		assertThat(ApiKeyService.hash(key)).isNotEqualTo(ApiKeyService.hash(key + "x"));
	}

	@Test
	void hash_matchesKnownSha256() {
		// SHA-256("abc") from the official test vectors.
		assertThat(ApiKeyService.hash("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void looksLikeKey_rejectsMalformedValues() {
		assertThat(ApiKeyService.looksLikeKey(service.generateKey())).isTrue();
		assertThat(ApiKeyService.looksLikeKey(null)).isFalse();
		assertThat(ApiKeyService.looksLikeKey("sb-short")).isFalse();
		assertThat(ApiKeyService.looksLikeKey("sk-" + "x".repeat(43))).isFalse();
		assertThat(ApiKeyService.looksLikeKey("sb-" + "x".repeat(200))).isFalse();
	}

	// ---------- create ----------

	@Test
	void create_storesOnlyHashAndPrefix_returnsFullKeyOnce() {
		var created = service.create(userId, "  laptop script  ").block();

		ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
		verify(repository).save(saved.capture());
		ApiKey row = saved.getValue();
		assertThat(row.id()).isNull();
		assertThat(row.userId()).isEqualTo(userId);
		assertThat(row.keyHash()).isEqualTo(ApiKeyService.hash(created.fullKey()));
		assertThat(row.keyHash()).doesNotContain(created.fullKey());
		assertThat(row.keyPrefix()).hasSize(11).isEqualTo(created.fullKey().substring(0, 11));
		assertThat(row.name()).isEqualTo("laptop script");
		assertThat(row.revokedAt()).isNull();
		assertThat(created.toString()).doesNotContain(created.fullKey());
	}

	@Test
	void create_atLimit_isRefused() {
		when(repository.countByUserIdAndRevokedAtIsNull(userId))
				.thenReturn(Mono.just((long) ApiKeyService.MAX_ACTIVE_KEYS_PER_USER));

		StepVerifier.create(service.create(userId, "one too many"))
				.expectError(ApiKeyService.TooManyKeysException.class).verify();
		verify(repository, never()).save(any());
	}

	// ---------- revoke ----------

	@Test
	void revoke_setsRevokedAt() {
		ApiKey key = new ApiKey(UUID.randomUUID(), userId, "h", "sb-abc", "k", Instant.now(), null, null);
		when(repository.findByIdAndUserId(key.id(), userId)).thenReturn(Mono.just(key));

		StepVerifier.create(service.revoke(userId, key.id()))
				.assertNext(k -> assertThat(k.revokedAt()).isNotNull())
				.verifyComplete();
	}

	@Test
	void revoke_alreadyRevoked_doesNotSaveAgain() {
		ApiKey key = new ApiKey(UUID.randomUUID(), userId, "h", "sb-abc", "k", Instant.now(), Instant.now(), null);
		when(repository.findByIdAndUserId(key.id(), userId)).thenReturn(Mono.just(key));

		StepVerifier.create(service.revoke(userId, key.id())).expectNext(key).verifyComplete();
		verify(repository, never()).save(any());
	}

	@Test
	void revoke_someoneElsesKey_isEmpty() {
		when(repository.findByIdAndUserId(any(), any())).thenReturn(Mono.empty());
		StepVerifier.create(service.revoke(userId, UUID.randomUUID())).verifyComplete();
	}

	// ---------- verify ----------

	@Test
	void verify_malformedKey_doesNotHitDatabase() {
		StepVerifier.create(service.verify("not-a-key")).verifyComplete();
		StepVerifier.create(service.verify(null)).verifyComplete();
		verify(repository, never()).findUsableByHash(anyString());
	}

	@Test
	void verify_validKey_looksUpByHashAndRecordsUse() {
		String key = service.generateKey();
		UsableKey usable = new UsableKey(UUID.randomUUID(), userId, "MEMBER");
		when(repository.findUsableByHash(ApiKeyService.hash(key))).thenReturn(Mono.just(usable));

		StepVerifier.create(service.verify(key)).expectNext(usable).verifyComplete();
		verify(repository, timeout(1000)).touchLastUsed(usable.apiKeyId());
	}

	@Test
	void verify_unknownKey_isEmpty() {
		when(repository.findUsableByHash(anyString())).thenReturn(Mono.empty());
		StepVerifier.create(service.verify(service.generateKey())).verifyComplete();
		verify(repository, never()).touchLastUsed(any());
	}

	@Test
	void verify_lastUsedUpdateFails_stillAuthenticates() {
		String key = service.generateKey();
		UsableKey usable = new UsableKey(UUID.randomUUID(), userId, "MEMBER");
		when(repository.findUsableByHash(anyString())).thenReturn(Mono.just(usable));
		when(repository.touchLastUsed(any())).thenReturn(Mono.error(new RuntimeException("db busy")));

		StepVerifier.create(service.verify(key)).expectNext(usable).verifyComplete();
	}
}
