package com.switchboard.gateway.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.provider.ollama.OllamaProperties;
import com.switchboard.gateway.requestlog.RequestLog;
import com.switchboard.gateway.requestlog.RequestLogger;

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

	/** Fake logger: keeps entries in a list instead of writing to a database. */
	static class CapturingLogger extends RequestLogger {
		final List<RequestLog> entries = new CopyOnWriteArrayList<>();
		boolean fail;

		CapturingLogger() {
			super(null);
		}

		@Override
		public void record(RequestLog entry) {
			if (fail) {
				throw new IllegalStateException("must never be called in a way that can throw");
			}
			entries.add(entry);
		}
	}

	private final CapturingLogger logger = new CapturingLogger();
	private final FakeProvider provider = new FakeProvider();
	private final ChatCompletionService service = new ChatCompletionService(provider, PROPS, logger);

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
		var timed = new ChatCompletionService(provider, PROPS, logger, ticking);

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

	// ---------- request logging ----------

	@Test
	void success_isLoggedWithAllMetadata() {
		UUID user = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		service.complete(new ChatCompletionRequest("auto", MESSAGES), new RequestContext(user, key)).block();

		assertThat(logger.entries).hasSize(1);
		RequestLog log = logger.entries.get(0);
		assertThat(log.id()).isNull(); // database generates it
		assertThat(log.userId()).isEqualTo(user);
		assertThat(log.apiKeyId()).isEqualTo(key);
		assertThat(log.provider()).isEqualTo("fake");
		assertThat(log.model()).isEqualTo("llama3.2");
		assertThat(log.routerScore()).isNull();
		assertThat(log.tokensIn()).isEqualTo(7);
		assertThat(log.tokensOut()).isEqualTo(3);
		assertThat(log.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(log.latencyMs()).isGreaterThanOrEqualTo(0);
		assertThat(log.cacheHit()).isFalse();
		assertThat(log.status()).isEqualTo(RequestLog.Status.SUCCESS);
		assertThat(log.errorCode()).isNull();
		assertThat(log.blockedReason()).isNull();
		assertThat(log.createdAt()).isNotNull();
	}

	@Test
	void anonymousRequest_isLoggedWithoutUser() {
		service.complete(new ChatCompletionRequest("auto", MESSAGES)).block();
		assertThat(logger.entries).singleElement().satisfies(l -> {
			assertThat(l.userId()).isNull();
			assertThat(l.apiKeyId()).isNull();
		});
	}

	@Test
	void providerFailure_isLoggedAsError_andErrorStillReachesCaller() {
		provider.reply = Mono.error(new ProviderException("ollama", "down", null));

		StepVerifier.create(service.complete(new ChatCompletionRequest("qwen2.5:7b", MESSAGES)))
				.expectError(ProviderException.class).verify();

		assertThat(logger.entries).singleElement().satisfies(l -> {
			assertThat(l.status()).isEqualTo(RequestLog.Status.ERROR);
			assertThat(l.errorCode()).isEqualTo("ollama_unavailable");
			assertThat(l.model()).isEqualTo("qwen2.5:7b");
			assertThat(l.provider()).isEqualTo("fake");
			assertThat(l.tokensIn()).isZero();
			assertThat(l.tokensOut()).isZero();
		});
	}

	@Test
	void unexpectedFailure_isLoggedAsServerError() {
		provider.reply = Mono.error(new IllegalStateException("bug"));

		StepVerifier.create(service.complete(new ChatCompletionRequest("auto", MESSAGES)))
				.expectError(IllegalStateException.class).verify();
		assertThat(logger.entries).singleElement()
				.satisfies(l -> assertThat(l.errorCode()).isEqualTo("server_error"));
	}

	@Test
	void eachRequest_isLoggedExactlyOnce() {
		for (int i = 0; i < 5; i++) {
			service.complete(new ChatCompletionRequest("auto", MESSAGES)).block();
		}
		assertThat(logger.entries).hasSize(5);
	}

	@Test
	void notSubscribed_meansNothingLogged() {
		service.complete(new ChatCompletionRequest("auto", MESSAGES)); // never subscribed
		assertThat(logger.entries).isEmpty();
	}

	@Test
	void logEntry_hasNoPlaceForPromptOrReplyText() {
		// Structural privacy check: RequestLog has no text field that could hold the conversation.
		var fieldNames = java.util.Arrays.stream(RequestLog.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName).toList();
		assertThat(fieldNames).doesNotContain("prompt", "messages", "content", "reply", "response");
	}

	// ---------- usage limits ----------

	/** Policy that can block, and records the spend it was given. */
	static class FakePolicy implements UsagePolicy {
		RuntimeException block;
		final List<BigDecimal> spent = new CopyOnWriteArrayList<>();

		String switchTo;

		@Override
		public Mono<String> check(RequestContext context, String model, boolean auto) {
			if (block != null) {
				return Mono.error(block);
			}
			return Mono.just(switchTo != null ? switchTo : model);
		}

		@Override
		public void recordSpend(RequestContext context, BigDecimal costUsd) {
			spent.add(costUsd);
		}
	}

	@Test
	void blockedByPolicy_providerNotCalled_loggedAsBlocked() {
		FakePolicy policy = new FakePolicy();
		policy.block = new com.switchboard.gateway.limits.RequestBlockedException.RateLimited(5, 30);
		var limited = new ChatCompletionService(provider, PROPS, logger, policy, java.time.Clock.systemUTC());
		UUID user = UUID.randomUUID();

		StepVerifier.create(limited.complete(new ChatCompletionRequest("auto", MESSAGES),
				new RequestContext(user, null, "MEMBER")))
				.expectError(com.switchboard.gateway.limits.RequestBlockedException.RateLimited.class).verify();

		assertThat(provider.lastRequest.get()).isNull();
		assertThat(logger.entries).singleElement().satisfies(l -> {
			assertThat(l.status()).isEqualTo(RequestLog.Status.BLOCKED);
			assertThat(l.blockedReason()).isEqualTo("rate_limit_exceeded");
			assertThat(l.userId()).isEqualTo(user);
			assertThat(l.errorCode()).isNull();
		});
		assertThat(policy.spent).isEmpty();
	}

	@Test
	void success_recordsSpendWithPolicy() {
		FakePolicy policy = new FakePolicy();
		var limited = new ChatCompletionService(provider, PROPS, logger, policy, java.time.Clock.systemUTC());
		limited.complete(new ChatCompletionRequest("auto", MESSAGES), new RequestContext(UUID.randomUUID(), null))
				.block();
		assertThat(policy.spent).containsExactly(BigDecimal.ZERO);
	}

	@Test
	void providerFailure_recordsNoSpend() {
		FakePolicy policy = new FakePolicy();
		provider.reply = Mono.error(new ProviderException("ollama", "down", null));
		var limited = new ChatCompletionService(provider, PROPS, logger, policy, java.time.Clock.systemUTC());
		StepVerifier.create(limited.complete(new ChatCompletionRequest("auto", MESSAGES))).expectError().verify();
		assertThat(policy.spent).isEmpty();
	}

	@Test
	void policyChoosesModel_providerGetsIt() {
		FakePolicy policy = new FakePolicy();
		policy.switchTo = "qwen2.5:7b";
		provider.reply = Mono.just(new ProviderResponse(null, "ok", "stop", 1, 1));
		var limited = new ChatCompletionService(provider, PROPS, logger, policy, java.time.Clock.systemUTC());

		StepVerifier.create(limited.complete(new ChatCompletionRequest("auto", MESSAGES)))
				.assertNext(r -> assertThat(r.model()).isEqualTo("qwen2.5:7b"))
				.verifyComplete();
		assertThat(provider.lastRequest.get().model()).isEqualTo("qwen2.5:7b");
	}

	@Test
	void blockedAutoRequest_isLoggedAsAuto() {
		FakePolicy policy = new FakePolicy();
		policy.block = new com.switchboard.gateway.limits.RequestBlockedException.ModelNotAllowed("auto", List.of());
		var limited = new ChatCompletionService(provider, PROPS, logger, policy, java.time.Clock.systemUTC());
		StepVerifier.create(limited.complete(new ChatCompletionRequest(null, MESSAGES))).expectError().verify();
		assertThat(logger.entries).singleElement().satisfies(l -> {
			assertThat(l.model()).isEqualTo("auto");
			assertThat(l.blockedReason()).isEqualTo("model_not_allowed");
		});
	}

	@Test
	void isAuto_detectsAutoVariants() {
		assertThat(ChatCompletionService.isAuto(null)).isTrue();
		assertThat(ChatCompletionService.isAuto(" ")).isTrue();
		assertThat(ChatCompletionService.isAuto("Auto")).isTrue();
		assertThat(ChatCompletionService.isAuto("llama3.2")).isFalse();
	}
}
