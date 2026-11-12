package com.switchboard.gateway.provider;

import java.util.List;

import com.switchboard.gateway.api.dto.ChatMessage;

/**
 * What the gateway asks a provider: a concrete model name, the conversation, and optionally the
 * client's max_tokens (null = provider default).
 */
public record ProviderRequest(String model, List<ChatMessage> messages, Integer maxTokens) {

	public ProviderRequest(String model, List<ChatMessage> messages) {
		this(model, messages, null);
	}
}
