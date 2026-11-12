package com.switchboard.gateway.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

/**
 * Body of POST /v1/chat/completions (OpenAI-compatible).
 *
 * @param model    "auto" (let Switchboard choose) or a specific model name; null is treated as "auto"
 * @param messages  the conversation so far; must not be empty
 * @param maxTokens optional cap on the reply length (OpenAI's "max_tokens"); used by Claude models
 */
public record ChatCompletionRequest(
		String model,
		@NotEmpty List<@Valid ChatMessage> messages,
		@JsonProperty("max_tokens") @Min(1) @Max(128000) Integer maxTokens) {

	public ChatCompletionRequest(String model, List<ChatMessage> messages) {
		this(model, messages, null);
	}
}
