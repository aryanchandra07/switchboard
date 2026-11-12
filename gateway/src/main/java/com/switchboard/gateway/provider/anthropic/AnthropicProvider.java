package com.switchboard.gateway.provider.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.OverloadedException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.InvalidProviderRequestException;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.providerkey.ProviderKeyService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Calls Claude through the official Anthropic Java SDK (FR-10).
 *
 * <ul>
 * <li>The API key is the one the admin stored (encrypted) with PUT /admin/providers/anthropic. It is
 * decrypted only here, in memory, and never logged.</li>
 * <li>Only models with a configured price can be called, so every call has a known cost (FR-09) that
 * counts against member budgets.</li>
 * <li>The SDK's own retries are turned off: retries and failover are the gateway's job (Step 13),
 * so they happen in one place and can't multiply.</li>
 * </ul>
 */
@Component
public class AnthropicProvider implements LlmProvider {

	private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

	public static final String NAME = "anthropic";
	static final String PROVIDER_KEY_NAME = "anthropic";

	private final ProviderKeyService providerKeys;
	private final AnthropicProperties properties;

	/** One SDK client per API key; rebuilt when the admin rotates the key. */
	private volatile CachedClient cached;

	public AnthropicProvider(ProviderKeyService providerKeys, AnthropicProperties properties) {
		this.providerKeys = providerKeys;
		this.properties = properties;
	}

	@Override
	public String name() {
		return NAME;
	}

	/** The models this gateway can call (the ones with a price). None while Claude is switched off. */
	@Override
	public Flux<String> listModels() {
		return properties.enabled() ? Flux.fromIterable(properties.prices().keySet()).sort() : Flux.empty();
	}

	@Override
	public Mono<ProviderResponse> chat(ProviderRequest request) {
		if (!properties.enabled()) {
			return Mono.error(new InvalidProviderRequestException(NAME, "Claude models are switched off on this "
					+ "gateway, so it stays free. Use a local model such as llama3.2, or ask the admin to enable "
					+ "Claude (SWITCHBOARD_ANTHROPIC_ENABLED=true)"));
		}
		var price = properties.priceOf(request.model());
		if (price.isEmpty()) {
			return Mono.error(new InvalidProviderRequestException(NAME, "Model '" + request.model()
					+ "' is not available: the gateway has no price for it. Available: "
					+ String.join(", ", properties.prices().keySet().stream().sorted().toList())));
		}
		MessageCreateParams params;
		try {
			params = toParams(request);
		}
		catch (IllegalArgumentException e) {
			return Mono.error(new InvalidProviderRequestException(NAME, e.getMessage()));
		}

		return providerKeys.decryptedKey(PROVIDER_KEY_NAME)
				.switchIfEmpty(Mono.error(new ProviderException(NAME,
						"No Anthropic API key is stored. The admin can add one with PUT /admin/providers/anthropic",
						null, ProviderException.Kind.PERMANENT)))
				// The SDK's async client returns a CompletableFuture; Mono.fromFuture adapts it to Reactor.
				.flatMap(key -> Mono.fromFuture(() -> clientFor(key).async().messages().create(params)))
				.map(message -> toProviderResponse(message, price.get()))
				.onErrorMap(e -> !(e instanceof ProviderException), AnthropicProvider::toProviderException);
	}

	// ---------- request mapping ----------

	/**
	 * OpenAI-style messages → Anthropic Messages API. System messages become the top-level system
	 * prompt (joined in order); user/assistant messages keep their order.
	 */
	MessageCreateParams toParams(ProviderRequest request) {
		int maxTokens = request.maxTokens() == null ? properties.defaultMaxTokens() : request.maxTokens();
		if (maxTokens < 1 || maxTokens > properties.maxMaxTokens()) {
			throw new IllegalArgumentException("max_tokens must be between 1 and " + properties.maxMaxTokens());
		}
		MessageCreateParams.Builder b = MessageCreateParams.builder()
				.model(request.model())
				.maxTokens((long) maxTokens);

		List<String> system = new ArrayList<>();
		boolean hasConversation = false;
		for (ChatMessage m : request.messages()) {
			switch (m.role()) {
				case "system", "developer" -> system.add(m.content());
				case "user" -> {
					b.addUserMessage(m.content());
					hasConversation = true;
				}
				case "assistant" -> {
					b.addAssistantMessage(m.content());
					hasConversation = true;
				}
				default -> throw new IllegalArgumentException("Unsupported message role '" + m.role()
						+ "' for Claude (use system, user or assistant)");
			}
		}
		if (!hasConversation) {
			throw new IllegalArgumentException("Claude needs at least one user message");
		}
		if (!system.isEmpty()) {
			b.system(String.join("\n\n", system));
		}
		return b.build();
	}

	// ---------- response mapping ----------

	static ProviderResponse toProviderResponse(Message message, AnthropicProperties.Price price) {
		StringBuilder text = new StringBuilder();
		for (ContentBlock block : message.content()) {
			block.text().ifPresent(t -> text.append(t.text())); // thinking blocks etc. are skipped
		}
		long in = message.usage().inputTokens();
		long out = message.usage().outputTokens();
		return new ProviderResponse(
				message.model().asString(),
				text.toString(),
				finishReason(message.stopReason().orElse(null)),
				(int) in,
				(int) out,
				price.cost(in, out));
	}

	/** Anthropic stop reasons → OpenAI finish_reason values. */
	static String finishReason(StopReason reason) {
		if (reason == null) {
			return "stop";
		}
		String r = reason.asString();
		return switch (r) {
			case "max_tokens" -> "length";
			case "refusal" -> "content_filter";
			case "tool_use" -> "tool_calls";
			default -> "stop"; // end_turn, stop_sequence, pause_turn
		};
	}

	// ---------- errors ----------

	/** Turns SDK errors into a ProviderException with a message that is safe to show a client. */
	static ProviderException toProviderException(Throwable e) {
		Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
		String message;
		ProviderException.Kind kind = ProviderException.Kind.TRANSIENT;
		if (cause instanceof UnauthorizedException || cause instanceof PermissionDeniedException) {
			kind = ProviderException.Kind.PERMANENT;
			message = "Anthropic rejected the stored API key (HTTP " + ((AnthropicServiceException) cause).statusCode()
					+ "). The admin should check or rotate it";
		}
		else if (cause instanceof RateLimitException) {
			message = "Anthropic rate limit reached (HTTP 429). Try again shortly";
		}
		else if (cause instanceof OverloadedException) {
			message = "Anthropic is temporarily overloaded (HTTP 529). Try again shortly";
		}
		else if (cause instanceof AnthropicServiceException s) {
			message = "Anthropic returned HTTP " + s.statusCode();
			if (s.statusCode() >= 400 && s.statusCode() < 500 && s.statusCode() != 408) {
				kind = ProviderException.Kind.PERMANENT; // bad request, not found, too large...
			}
		}
		else if (cause instanceof AnthropicIoException) {
			message = "Could not reach Anthropic (network error or timeout)";
		}
		else {
			message = "Anthropic call failed";
		}
		log.warn("Anthropic call failed: {}", cause.toString());
		return new ProviderException(NAME, message, cause, kind);
	}

	// ---------- client ----------

	private AnthropicClient clientFor(String apiKey) {
		CachedClient current = cached;
		if (current != null && current.apiKey().equals(apiKey)) {
			return current.client();
		}
		synchronized (this) {
			if (cached != null && cached.apiKey().equals(apiKey)) {
				return cached.client();
			}
			AnthropicClient client = AnthropicOkHttpClient.builder()
					.apiKey(apiKey)
					.baseUrl(properties.baseUrl())
					.timeout(properties.timeout())
					.maxRetries(0)
					.build();
			CachedClient old = cached;
			cached = new CachedClient(apiKey, client);
			if (old != null) {
				old.client().close(); // key was rotated
			}
			return client;
		}
	}

	private record CachedClient(String apiKey, AnthropicClient client) {
		@Override
		public String toString() {
			return "CachedClient[apiKey=****]";
		}
	}
}
