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
import com.switchboard.gateway.limits.RequestBlockedException;
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
	private final UsagePolicy usagePolicy;
	private final Clock clock;

	@Autowired // several constructors exist, so tell Spring which one to use
	public ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties,
			RequestLogger requestLogger, UsagePolicy usagePolicy) {
		this(provider, ollamaProperties, requestLogger, usagePolicy, Clock.systemUTC());
	}

	/** Without limits; for unit tests. */
	ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties, RequestLogger requestLogger) {
		this(provider, ollamaProperties, requestLogger, UsagePolicy.NONE, Clock.systemUTC());
	}

	ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties, RequestLogger requestLogger,
			Clock clock) {
		this(provider, ollamaProperties, requestLogger, UsagePolicy.NONE, clock);
	}

	ChatCompletionService(LlmProvider provider, OllamaProperties ollamaProperties, RequestLogger requestLogger,
			UsagePolicy usagePolicy, Clock clock) {
		this.provider = provider;
		this.ollamaProperties = ollamaProperties;
		this.requestLogger = requestLogger;
		this.usagePolicy = usagePolicy;
		this.clock = clock;
	}

	public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
		return complete(request, RequestContext.anonymous());
	}

	public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request, RequestContext context) {
		boolean auto = isAuto(request.model());
		String requested = resolveModel(request.model());

		// 1. Limits (allowed model, rate, budget) decide the final model.
		//    A refusal is logged as BLOCKED and the model is never called.
		Mono<String> chosenModel = Mono.defer(() -> usagePolicy.check(context, requested, auto))
				.doOnError(RequestBlockedException.class,
						e -> requestLogger.record(blockedLog(e, auto ? "auto" : requested, context)));

		// 2. Model call. Mono.defer so the start time is taken when it actually runs.
		return chosenModel.flatMap(model -> Mono.defer(() -> {
			long start = clock.millis();
			return provider.chat(new ProviderRequest(model, request.messages()))
					.map(r -> toResponse(r, model, clock.millis() - start))
					.doOnNext(response -> {
						requestLogger.record(successLog(response, context));
						usagePolicy.recordSpend(context, response.switchboard().costUsd());
					})
					.doOnError(e -> requestLogger.record(errorLog(e, model, clock.millis() - start, context)));
		}));
	}

	static boolean isAuto(String requested) {
		return requested == null || requested.isBlank() || AUTO.equalsIgnoreCase(requested.trim());
	}

	/** "auto" (or no model) → default model. Person B's router will replace this later. */
	String resolveModel(String requested) {
		return isAuto(requested) ? ollamaProperties.defaultModel() : requested.trim();
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

	private RequestLog blockedLog(RequestBlockedException e, String model, RequestContext ctx) {
		return new RequestLog(null, ctx.userId(), ctx.apiKeyId(), null, model, null, 0, 0, BigDecimal.ZERO, 0,
				false, RequestLog.Status.BLOCKED, null, e.reason(), now());
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS); // Postgres precision
	}
}
