package com.switchboard.gateway.chat;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatCompletionResponse;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.provider.ollama.OllamaProperties;

import reactor.core.publisher.Mono;

/**
 * The request pipeline. For now: pick a model, call the provider, build the OpenAI-style reply.
 * Auth, guardrails, cache, router, failover and logging will slot in here in later steps.
 */
@Service
public class ChatCompletionService {

	static final String AUTO = "auto";

	private final LlmProvider provider;
	private final OllamaProperties ollamaProperties;
	private final Clock clock;

	@Autowired // two constructors exist, so tell Spring which one to use
	public ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties) {
		this(provider, ollamaProperties, Clock.systemUTC());
	}

	ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties, Clock clock) {
		this.provider = provider;
		this.ollamaProperties = ollamaProperties;
		this.clock = clock;
	}

	public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
		String model = resolveModel(request.model());
		ProviderRequest providerRequest = new ProviderRequest(model, request.messages());

		// Mono.defer so the start time is taken when someone subscribes, not when this method runs.
		return Mono.defer(() -> {
			long start = clock.millis();
			return provider.chat(providerRequest)
					.map(r -> toResponse(r, model, clock.millis() - start));
		});
	}

	/** "auto" (or no model) → default model. Person B's router will replace this later. */
	String resolveModel(String requested) {
		if (requested == null || requested.isBlank() || AUTO.equalsIgnoreCase(requested.trim())) {
			return ollamaProperties.defaultModel();
		}
		return requested.trim();
	}

	private ChatCompletionResponse toResponse(ProviderResponse r, String requestedModel, long latencyMs) {
		String modelUsed = r.model() == null ? requestedModel : r.model();
		var choice = new ChatCompletionResponse.Choice(0,
				new ChatMessage("assistant", r.content()), r.finishReason());
		var usage = new ChatCompletionResponse.Usage(r.promptTokens(), r.completionTokens(),
				r.promptTokens() + r.completionTokens());
		var meta = new ChatCompletionResponse.Switchboard(provider.name(), modelUsed,
				null, BigDecimal.ZERO, latencyMs, false); // local models are free; no cache yet

		return new ChatCompletionResponse(
				"chatcmpl-" + UUID.randomUUID(),
				"chat.completion",
				clock.millis() / 1000,
				modelUsed,
				List.of(choice),
				usage,
				meta);
	}
}
