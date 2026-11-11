package com.switchboard.gateway.chat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatCompletionResponse;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.provider.ollama.OllamaProperties;
import com.switchboard.gateway.requestlog.RequestLog;
import com.switchboard.gateway.requestlog.RequestLogger;

import reactor.core.publisher.Mono;

/**
 * The request pipeline. For now: pick a model, call the provider, build the OpenAI-style reply,
 * and log the outcome. Auth, guardrails, cache, router and failover will slot in here later.
 */
@Service
public class ChatCompletionService {

	static final String AUTO = "auto";

	private final LlmProvider provider;
	private final OllamaProperties ollamaProperties;
	private final RequestLogger requestLogger;
	private final Clock clock;

	@Autowired // two constructors exist, so tell Spring which one to use
	public ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties,
			RequestLogger requestLogger) {
		this(provider, ollamaProperties, requestLogger, Clock.systemUTC());
	}

	ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties, RequestLogger requestLogger,
			Clock clock) {
		this.provider = provider;
		this.ollamaProperties = ollamaProperties;
		this.requestLogger = requestLogger;
		this.clock = clock;
	}

	public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
		return complete(request, RequestContext.anonymous());
	}

	public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request, RequestContext context) {
		String model = resolveModel(request.model());
		ProviderRequest providerRequest = new ProviderRequest(model, request.messages());

		// Mono.defer so the start time is taken when someone subscribes, not when this method runs.
		return Mono.defer(() -> {
			long start = clock.millis();
			return provider.chat(providerRequest)
					.map(r -> toResponse(r, model, clock.millis() - start))
					.doOnNext(response -> requestLogger.record(successLog(response, context)))
					.doOnError(e -> requestLogger.record(errorLog(e, model, clock.millis() - start, context)));
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

	private RequestLog successLog(ChatCompletionResponse r, RequestContext ctx) {
		var meta = r.switchboard();
		return new RequestLog(null, ctx.userId(), ctx.apiKeyId(), meta.provider(), meta.modelUsed(),
				meta.routerScore(), r.usage().promptTokens(), r.usage().completionTokens(), meta.costUsd(),
				(int) meta.latencyMs(), meta.cacheHit(), RequestLog.Status.SUCCESS, null, null, now());
	}

	private RequestLog errorLog(Throwable e, String model, long latencyMs, RequestContext ctx) {
		String code = e instanceof ProviderException pe ? pe.provider() + "_unavailable" : "server_error";
		return new RequestLog(null, ctx.userId(), ctx.apiKeyId(), provider.name(), model, null, 0, 0,
				BigDecimal.ZERO, (int) Math.max(0, latencyMs), false, RequestLog.Status.ERROR, code, null, now());
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS); // Postgres precision
	}
}
