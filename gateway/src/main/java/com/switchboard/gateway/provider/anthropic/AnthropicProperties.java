package com.switchboard.gateway.provider.anthropic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Claude (Anthropic) provider (prefix "switchboard.anthropic").
 *
 * @param enabled          false (the default) keeps the project 100% free: claude-... models are refused
 *                         and hidden, and Anthropic is never contacted, even if a key is stored
 * @param baseUrl          Anthropic API address (changed only in tests)
 * @param timeout          how long to wait for a reply
 * @param defaultMaxTokens max_tokens sent when the client doesn't set one
 * @param maxMaxTokens     the most a client may ask for (non-streaming requests time out above this)
 * @param prices           USD per 1 million tokens, per model. Only models listed here can be called,
 *                         so every Claude request has a known cost and counts against budgets.
 */
@ConfigurationProperties(prefix = "switchboard.anthropic")
public record AnthropicProperties(
		boolean enabled,
		String baseUrl,
		Duration timeout,
		int defaultMaxTokens,
		int maxMaxTokens,
		Map<String, Price> prices) {

	public AnthropicProperties {
		prices = prices == null ? Map.of() : Map.copyOf(prices);
		if (defaultMaxTokens < 1 || maxMaxTokens < defaultMaxTokens) {
			throw new IllegalStateException("switchboard.anthropic max-tokens settings are invalid");
		}
	}

	public Optional<Price> priceOf(String model) {
		return Optional.ofNullable(prices.get(model));
	}

	/** USD per 1 million input and output tokens. */
	public record Price(BigDecimal input, BigDecimal output) {

		private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

		/** Cost of one call, rounded to micro-dollars (the precision stored in the database). */
		public BigDecimal cost(long inputTokens, long outputTokens) {
			return input.multiply(BigDecimal.valueOf(inputTokens))
					.add(output.multiply(BigDecimal.valueOf(outputTokens)))
					.divide(MILLION, 6, RoundingMode.HALF_UP);
		}
	}
}
