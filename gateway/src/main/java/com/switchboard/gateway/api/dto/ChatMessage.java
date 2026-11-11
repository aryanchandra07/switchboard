package com.switchboard.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One message in a conversation, e.g. {"role": "user", "content": "Hi"}. */
public record ChatMessage(
		@NotBlank String role,
		@NotNull String content) {
}
