package com.switchboard.gateway.providerkey;

import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Admin-only management of provider API keys. SecurityConfig restricts /admin/** to ADMIN.
 * Responses never include the key, only a hint (last 4 characters).
 */
@RestController
@RequestMapping("/admin/providers")
public class AdminProviderController {

	private final ProviderKeyService service;

	public AdminProviderController(ProviderKeyService service) {
		this.service = service;
	}

	@GetMapping
	public Flux<ProviderKeyView> list() {
		return service.list().map(ProviderKeyView::of);
	}

	/** Add a key, or rotate (replace) the existing one. */
	@PutMapping(path = "/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ProviderKeyView> save(@PathVariable String provider, @Valid @RequestBody SaveKeyRequest request) {
		return service.save(provider, request.apiKey(), request.label()).map(ProviderKeyView::of);
	}

	public record SaveKeyRequest(
			@JsonProperty("api_key")
			@NotBlank
			@Size(min = 8, max = 500)
			@Pattern(regexp = "\\S+", message = "must not contain spaces or line breaks")
			String apiKey,
			@Size(max = 100) String label) {

		@Override
		public String toString() {
			return "SaveKeyRequest[label=" + label + ", apiKey=****]"; // never log the key
		}
	}

	/** What the admin sees. Deliberately has no field for the key itself. */
	public record ProviderKeyView(
			String provider,
			String label,
			@JsonProperty("key_hint") String keyHint,
			@JsonProperty("created_at") Instant createdAt,
			@JsonProperty("rotated_at") Instant rotatedAt) {

		static ProviderKeyView of(ProviderKey k) {
			return new ProviderKeyView(k.provider(), k.label(), "..." + k.keyHint(), k.createdAt(), k.rotatedAt());
		}
	}
}
