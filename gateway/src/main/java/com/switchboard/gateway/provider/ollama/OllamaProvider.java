package com.switchboard.gateway.provider.ollama;

import java.util.List;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Calls Ollama's native chat API: POST {baseUrl}/api/chat with "stream": false.
 * Docs: https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 */
@Component
public class OllamaProvider implements LlmProvider {

	private final WebClient webClient;
	private final OllamaProperties properties;

	public OllamaProvider(OllamaProperties properties) {
		this.properties = properties;
		this.webClient = WebClient.builder().baseUrl(properties.baseUrl()).build();
	}

	@Override
	public String name() {
		return "ollama";
	}

	@Override
	public Mono<ProviderResponse> chat(ProviderRequest request) {
		OllamaChatRequest body = new OllamaChatRequest(request.model(), request.messages(), false);

		return webClient.post()
				.uri("/api/chat")
				.bodyValue(body)
				.retrieve() // turns 4xx/5xx from Ollama into an error signal
				.bodyToMono(OllamaChatResponse.class)
				.timeout(properties.timeout())
				.map(OllamaProvider::toProviderResponse)
				.onErrorMap(e -> !(e instanceof ProviderException), this::toProviderException);
	}

	/** Installed models from GET /api/tags. ":latest" is dropped, so "llama3.2:latest" becomes "llama3.2". */
	@Override
	public Flux<String> listModels() {
		return webClient.get()
				.uri("/api/tags")
				.retrieve()
				.bodyToMono(OllamaTagsResponse.class)
				.timeout(java.time.Duration.ofSeconds(5))
				.flatMapMany(t -> Flux.fromIterable(t.models() == null ? List.of() : t.models()))
				.map(m -> m.name().endsWith(":latest") ? m.name().substring(0, m.name().length() - 7) : m.name());
	}

	private static ProviderResponse toProviderResponse(OllamaChatResponse r) {
		return new ProviderResponse(
				r.model(),
				r.message() == null || r.message().content() == null ? "" : r.message().content(),
				r.doneReason() == null ? "stop" : r.doneReason(),
				r.promptEvalCount() == null ? 0 : r.promptEvalCount(),
				r.evalCount() == null ? 0 : r.evalCount());
	}

	/** Wraps any failure in a ProviderException with a message that is safe to show a client. */
	private ProviderException toProviderException(Throwable e) {
		String message;
		if (e instanceof TimeoutException) {
			message = "Ollama did not respond within " + properties.timeout().toSeconds() + "s";
		}
		else if (e instanceof WebClientResponseException w) {
			message = "Ollama returned HTTP " + w.getStatusCode().value() + ": " + w.getResponseBodyAsString();
		}
		else {
			message = "Could not reach Ollama at " + properties.baseUrl();
		}
		return new ProviderException(name(), message, e);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OllamaTagsResponse(List<OllamaModel> models) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OllamaModel(String name) {
	}

	/** JSON we send to Ollama. */
	record OllamaChatRequest(String model, List<ChatMessage> messages, boolean stream) {
	}

	/** The parts of Ollama's JSON reply we care about; everything else is ignored. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record OllamaChatResponse(
			String model,
			ChatMessage message,
			@JsonProperty("done_reason") String doneReason,
			@JsonProperty("prompt_eval_count") Integer promptEvalCount,
			@JsonProperty("eval_count") Integer evalCount) {
	}
}
