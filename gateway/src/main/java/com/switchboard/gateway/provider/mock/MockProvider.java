package com.switchboard.gateway.provider.mock;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A fake model that answers instantly with a fixed reply (FR-10). Used for load tests (to measure the
 * gateway itself, not a model) and for testing failover (via the optional failure rate). Costs nothing.
 */
@Component
public class MockProvider implements LlmProvider {

	public static final String NAME = "mock";
	static final String REPLY = "This is a mock reply from Switchboard.";
	private static final int REPLY_TOKENS = 8;

	private final MockProperties properties;
	private final DoubleSupplier random;

	@Autowired
	public MockProvider(MockProperties properties) {
		this(properties, () -> ThreadLocalRandom.current().nextDouble());
	}

	MockProvider(MockProperties properties, DoubleSupplier random) {
		this.properties = properties;
		this.random = random;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Mono<ProviderResponse> chat(ProviderRequest request) {
		Mono<ProviderResponse> reply = Mono.fromSupplier(() -> {
			if (properties.failureRate() > 0 && random.getAsDouble() < properties.failureRate()) {
				throw new ProviderException(NAME, "Mock provider simulated a failure", null);
			}
			return new ProviderResponse(request.model(), REPLY, "stop", estimateTokens(request), REPLY_TOKENS);
		});
		return properties.latency().isZero() ? reply : reply.delaySubscription(properties.latency());
	}

	@Override
	public Flux<String> listModels() {
		return Flux.just(NAME);
	}

	/** Rough token count like real models report: about 4 characters per token, at least 1. */
	static int estimateTokens(ProviderRequest request) {
		int chars = request.messages().stream().map(ChatMessage::content).mapToInt(String::length).sum();
		return Math.max(1, (chars + 3) / 4);
	}
}
