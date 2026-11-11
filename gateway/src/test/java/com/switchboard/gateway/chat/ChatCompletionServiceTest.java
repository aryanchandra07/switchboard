package com.switchboard.gateway.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.provider.ollama.OllamaProperties;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests the pipeline logic with a fake provider: no network involved. */
class ChatCompletionServiceTest {

	private static final OllamaProperties PROPS =
			new OllamaProperties("http://unused", "llama3.2", Duration.ofSeconds(5));
	private static final List<ChatMessage> MESSAGES = List.of(new ChatMessage("user", "Hi"));

	/** Fake provider: remembers what it was asked and returns a fixed reply. */
	static class FakeProvider implements LlmProvider {
		final AtomicReference<ProviderRequest> lastRequest = new AtomicReference<>();
		Mono<ProviderResponse> reply = Mono.just(new ProviderResponse("llama3.2", "Hello!", "stop", 7, 3));

		@Override
		public String name() {
			return "fake";
		}

		@Override
		public Mono<ProviderResponse> chat(ProviderRequest request) {
			lastRequest.set(request);
			return reply;
		}
	}

	private final FakeProvider provider = new FakeProvider();
	private final ChatCompletionService service = new ChatCompletionService(provider, PROPS);

	@Test
	void resolveModel_autoNullBlank_useDefaultModel() {
		assertThat(service.resolveModel("auto")).isEqualTo("llama3.2");
		assertThat(service.resolveModel("AUTO")).isEqualTo("llama3.2");
		assertThat(service.resolveModel(" auto ")).isEqualTo("llama3.2");
		assertThat(service.resolveModel(null)).isEqualTo("llama3.2");
		assertThat(service.resolveModel("")).isEqualTo("llama3.2");
		assertThat(service.resolveModel("   ")).isEqualTo("llama3.2");
	}

	@Test
	void resolveModel_namedModel_isPassedThroughTrimmed() {
		assertThat(service.resolveModel("qwen2.5:7b")).isEqualTo("qwen2.5:7b");
		assertThat(service.resolveModel("  llama3.2  ")).isEqualTo("llama3.2");
	}

	@Test
	void complete_auto_sendsDefaultModelAndMessagesToProvider() {
		StepVerifier.create(service.complete(new ChatCompletionRequest("auto", MESSAGES)))
				.expectNextCount(1).verifyComplete();

		assertThat(provider.lastRequest.get().model()).isEqualTo("llama3.2");
		assertThat(provider.lastRequest.get().messages()).isEqualTo(MESSAGES);
	}

	@Test
	void complete_namedModel_sendsThatModel() {
		provider.reply = Mono.just(new ProviderResponse("qwen2.5:7b", "ok", "stop", 1, 1));

		StepVerifier.create(service.complete(new ChatCompletionRequest("qwen2.5:7b", MESSAGES)))
				.assertNext(r -> assertThat(r.model()).isEqualTo("qwen2.5:7b"))
				.verifyComplete();

		assertThat(provider.lastRequest.get().model()).isEqualTo("qwen2.5:7b");
	}

	@Test
	void complete_buildsOpenAiShapedResponseWithMetadata() {
		StepVerifier.create(service.complete(new ChatCompletionRequest("auto", MESSAGES)))
				.assertNext(r -> {
					assertThat(r.id()).startsWith("chatcmpl-");
					assertThat(r.object()).isEqualTo("chat.completion");
					assertThat(r.created()).isPositive();
					assertThat(r.model()).isEqualTo("llama3.2");

					assertThat(r.choices()).hasSize(1);
					assertThat(r.choices().get(0).index()).isZero();
					assertThat(r.choices().get(0).message()).isEqualTo(new ChatMessage("assistant", "Hello!"));
					assertThat(r.choices().get(0).finishReason()).isEqualTo("stop");

					assertThat(r.usage().promptTokens()).isEqualTo(7);
					assertThat(r.usage().completionTokens()).isEqualTo(3);
					assertThat(r.usage().totalTokens()).isEqualTo(10);

					assertThat(r.switchboard().provider()).isEqualTo("fake");
					assertThat(r.switchboard().modelUsed()).isEqualTo("llama3.2");
					assertThat(r.switchboard().routerScore()).isNull();
					assertThat(r.switchboard().costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
					assertThat(r.switchboard().latencyMs()).isGreaterThanOrEqualTo(0);
					assertThat(r.switchboard().cacheHit()).isFalse();
				})
				.verifyComplete();
	}

	@Test
	void complete_eachResponseGetsUniqueId() {
		var a = service.complete(new ChatCompletionRequest("auto", MESSAGES)).block();
		var b = service.complete(new ChatCompletionRequest("auto", MESSAGES)).block();
		assertThat(a.id()).isNotEqualTo(b.id());
	}

	@Test
	void complete_providerReturnsNoModel_fallsBackToRequestedModel() {
		provider.reply = Mono.just(new ProviderResponse(null, "ok", "stop", 1, 1));

		StepVerifier.create(service.complete(new ChatCompletionRequest("auto", MESSAGES)))
				.assertNext(r -> {
					assertThat(r.model()).isEqualTo("llama3.2");
					assertThat(r.switchboard().modelUsed()).isEqualTo("llama3.2");
				})
				.verifyComplete();
	}

	@Test
	void complete_measuresLatencyAroundProviderCall() {
		// A clock that moves forward 250 ms each time it is read.
		Clock ticking = new Clock() {
			private long now = 1_000_000;

			@Override
			public long millis() {
				long t = now;
				now += 250;
				return t;
			}

			@Override
			public Instant instant() {
				return Instant.ofEpochMilli(millis());
			}

			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}
		};
		var timed = new ChatCompletionService(provider, PROPS, ticking);

		StepVerifier.create(timed.complete(new ChatCompletionRequest("auto", MESSAGES)))
				.assertNext(r -> assertThat(r.switchboard().latencyMs()).isEqualTo(250))
				.verifyComplete();
	}

	@Test
	void complete_providerFails_errorIsPassedOn() {
		provider.reply = Mono.error(new ProviderException("fake", "boom", null));

		StepVerifier.create(service.complete(new ChatCompletionRequest("auto", MESSAGES)))
				.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ProviderException.class).hasMessage("boom"))
				.verify();
	}

	@Test
	void complete_isLazy_providerNotCalledUntilSubscribed() {
		Mono<?> pending = service.complete(new ChatCompletionRequest("auto", MESSAGES));
		assertThat(provider.lastRequest.get()).isNull();

		pending.block();
		assertThat(provider.lastRequest.get()).isNotNull();
	}
}
