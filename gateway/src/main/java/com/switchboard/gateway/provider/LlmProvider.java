package com.switchboard.gateway.provider;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * One AI backend (Ollama, Anthropic, Mock...). Every provider has the same shape, so the
 * gateway can swap or fail over between them later (FR-10, FR-11).
 */
public interface LlmProvider {

	/** Short name, e.g. "ollama". */
	String name();

	/** Sends the conversation to the model. The Mono completes when the full reply arrives. */
	Mono<ProviderResponse> chat(ProviderRequest request);

	/** Model names this provider can serve right now. Empty if unknown. */
	default Flux<String> listModels() {
		return Flux.empty();
	}
}
