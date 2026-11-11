package com.switchboard.gateway.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response of POST /v1/chat/completions. Same shape as OpenAI's, plus a "switchboard" block
 * with routing metadata (FR-09).
 */
public record ChatCompletionResponse(
		String id,
		String object,
		long created,
		String model,
		List<Choice> choices,
		Usage usage,
		Switchboard switchboard) {

	public record Choice(
			int index,
			ChatMessage message,
			@JsonProperty("finish_reason") String finishReason) {
	}

	public record Usage(
			@JsonProperty("prompt_tokens") int promptTokens,
			@JsonProperty("completion_tokens") int completionTokens,
			@JsonProperty("total_tokens") int totalTokens) {
	}

	/** Routing metadata. routerScore is null until Person B's router service is wired in. */
	public record Switchboard(
			String provider,
			@JsonProperty("model_used") String modelUsed,
			@JsonProperty("router_score") Double routerScore,
			@JsonProperty("cost_usd") BigDecimal costUsd,
			@JsonProperty("latency_ms") long latencyMs,
			@JsonProperty("cache_hit") boolean cacheHit) {
	}
}
