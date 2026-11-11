package com.switchboard.gateway.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/**
 * Body of POST /v1/chat/completions (OpenAI-compatible).
 *
 * @param model    "auto" (let Switchboard choose) or a specific model name; null is treated as "auto"
 * @param messages the conversation so far; must not be empty
 */
public record ChatCompletionRequest(
		String model,
		@NotEmpty List<@Valid ChatMessage> messages) {
}
