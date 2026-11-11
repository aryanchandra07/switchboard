package com.switchboard.gateway.provider;

/** What a provider gives back, in a provider-neutral shape. */
public record ProviderResponse(
		String model,
		String content,
		String finishReason,
		int promptTokens,
		int completionTokens) {
}
