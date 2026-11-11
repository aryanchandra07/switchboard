package com.switchboard.gateway.apikey;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.switchboard.gateway.auth.AuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A logged-in user's own sb- keys (FR-05). Needs the web login token (JWT).
 * Every call re-checks that the account is still active.
 */
@RestController
@RequestMapping("/me/keys")
public class MyKeysController {

	private final ApiKeyService keys;
	private final AuthService auth;

	public MyKeysController(ApiKeyService keys, AuthService auth) {
		this.keys = keys;
		this.auth = auth;
	}

	@GetMapping
	public Flux<KeyView> list(@AuthenticationPrincipal Jwt jwt) {
		return activeUserId(jwt).flatMapMany(keys::list).map(KeyView::of);
	}

	/** The response is the only time the full key is ever shown. */
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<KeyView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateKeyRequest request) {
		return activeUserId(jwt).flatMap(userId -> keys.create(userId, request.name()))
				.map(created -> KeyView.withFullKey(created.key(), created.fullKey()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return activeUserId(jwt).flatMap(userId -> keys.revoke(userId, id))
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found")))
				.then();
	}

	private Mono<UUID> activeUserId(Jwt jwt) {
		return auth.currentUser(UUID.fromString(jwt.getSubject())).map(u -> u.id());
	}

	public record CreateKeyRequest(@NotBlank @Size(max = 50) String name) {
	}

	/** What the member sees. {@code key} is filled in only in the response to POST. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KeyView(
			UUID id,
			String name,
			String key,
			@JsonProperty("key_prefix") String keyPrefix,
			@JsonProperty("created_at") Instant createdAt,
			@JsonProperty("last_used_at") Instant lastUsedAt,
			@JsonProperty("revoked_at") Instant revokedAt,
			boolean active) {

		static KeyView of(ApiKey k) {
			return new KeyView(k.id(), k.name(), null, k.keyPrefix(), k.createdAt(), k.lastUsedAt(), k.revokedAt(),
					!k.isRevoked());
		}

		static KeyView withFullKey(ApiKey k, String fullKey) {
			return new KeyView(k.id(), k.name(), fullKey, k.keyPrefix(), k.createdAt(), k.lastUsedAt(),
					k.revokedAt(), !k.isRevoked());
		}

		@Override
		public String toString() {
			return "KeyView[id=" + id + ", prefix=" + keyPrefix + "]";
		}
	}
}
