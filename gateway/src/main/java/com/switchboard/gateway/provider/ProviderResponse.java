package com.switchboard.gateway.provider;

import java.math.BigDecimal;

/**
 * What a provider gives back, in a provider-neutral shape.
 *
 * @param costUsd what this call cost (0 for local models)
 */
public record ProviderResponse(
		String model,
		String content,
		String finishReason,
		int promptTokens,
		int completionTokens,
		BigDecimal costUsd) {

	/** A free call (local models, mock). */
	public ProviderResponse(String model, String content, String finishReason, int promptTokens,
			int completionTokens) {
		this(model, content, finishReason, promptTokens, completionTokens, BigDecimal.ZERO);
	}
}
