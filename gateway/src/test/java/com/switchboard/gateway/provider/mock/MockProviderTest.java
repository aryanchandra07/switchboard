package com.switchboard.gateway.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;

import reactor.test.StepVerifier;

class MockProviderTest {

	private static final ProviderRequest REQUEST = new ProviderRequest("mock",
			List.of(new ChatMessage("system", "Be brief."), new ChatMessage("user", "Hello there!")));

	private static MockProvider provider(Duration latency, double failureRate) {
		return new MockProvider(new MockProperties(true, latency, failureRate));
	}

	@Test
	void name_andModels() {
		MockProvider p = provider(Duration.ZERO, 0);
		assertThat(p.name()).isEqualTo("mock");
		StepVerifier.create(p.listModels()).expectNext("mock").verifyComplete();
	}

	@Test
	void reply_isFixedAndEchoesModel() {
		StepVerifier.create(provider(Duration.ZERO, 0).chat(new ProviderRequest("mock-fast", REQUEST.messages())))
				.assertNext(r -> {
					assertThat(r.model()).isEqualTo("mock-fast");
					assertThat(r.content()).isEqualTo("This is a mock reply from Switchboard.");
					assertThat(r.finishReason()).isEqualTo("stop");
					assertThat(r.completionTokens()).isEqualTo(8);
				})
				.verifyComplete();
	}

	@Test
	void promptTokens_areAboutFourCharactersEach() {
		// "Be brief." (9) + "Hello there!" (12) = 21 chars → 6 tokens (rounded up)
		assertThat(MockProvider.estimateTokens(REQUEST)).isEqualTo(6);
		assertThat(MockProvider.estimateTokens(new ProviderRequest("mock", List.of(new ChatMessage("user", "")))))
				.isEqualTo(1);
		assertThat(MockProvider.estimateTokens(new ProviderRequest("mock",
				List.of(new ChatMessage("user", "x".repeat(400)))))).isEqualTo(100);
	}

	@Test
	void noLatency_isInstant() {
		MockProvider p = provider(Duration.ZERO, 0);
		long start = System.nanoTime();
		for (int i = 0; i < 1000; i++) {
			p.chat(REQUEST).block();
		}
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000); // 1000 replies well under 1 s
	}

	@Test
	void configuredLatency_delaysReply() {
		StepVerifier.withVirtualTime(() -> provider(Duration.ofSeconds(2), 0).chat(REQUEST))
				.expectSubscription()
				.expectNoEvent(Duration.ofMillis(1999))
				.thenAwait(Duration.ofMillis(1))
				.expectNextCount(1)
				.verifyComplete();
	}

	@Test
	void failureRateOne_alwaysFails() {
		StepVerifier.create(provider(Duration.ZERO, 1.0).chat(REQUEST))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(ProviderException.class).hasMessageContaining("simulated");
					assertThat(((ProviderException) e).provider()).isEqualTo("mock");
				})
				.verify();
	}

	@Test
	void failureRateZero_neverFails() {
		MockProvider p = provider(Duration.ZERO, 0.0);
		for (int i = 0; i < 500; i++) {
			p.chat(REQUEST).block();
		}
	}

	@Test
	void failureRate_usesRandomDraw() {
		// Draws 0.1, 0.5, 0.9 against a 0.5 rate → fail, ok, ok.
		double[] draws = { 0.1, 0.5, 0.9 };
		AtomicInteger i = new AtomicInteger();
		MockProvider p = new MockProvider(new MockProperties(true, Duration.ZERO, 0.5), () -> draws[i.getAndIncrement()]);
		StepVerifier.create(p.chat(REQUEST)).expectError(ProviderException.class).verify();
		StepVerifier.create(p.chat(REQUEST)).expectNextCount(1).verifyComplete();
		StepVerifier.create(p.chat(REQUEST)).expectNextCount(1).verifyComplete();
	}

	@Test
	void eachSubscriptionIsANewCall() {
		// Retrying (re-subscribing) must draw again, so retries can succeed after a simulated failure.
		double[] draws = { 0.1, 0.9 };
		AtomicInteger i = new AtomicInteger();
		MockProvider p = new MockProvider(new MockProperties(true, Duration.ZERO, 0.5), () -> draws[i.getAndIncrement()]);
		StepVerifier.create(p.chat(REQUEST).retry(1)).expectNextCount(1).verifyComplete();
	}

	@Test
	void invalidSettings_areRejected() {
		assertThatThrownBy(() -> new MockProperties(true, Duration.ofMillis(-1), 0))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new MockProperties(true, Duration.ZERO, 1.5))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new MockProperties(true, Duration.ZERO, -0.1))
				.isInstanceOf(IllegalStateException.class);
		assertThat(new MockProperties(true, null, 0).latency()).isZero();
	}
}
