package com.switchboard.gateway.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderException.Kind;
import com.switchboard.gateway.provider.ProviderRegistry;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.provider.ollama.OllamaProperties;
import com.switchboard.gateway.requestlog.RequestLog;
import com.switchboard.gateway.resilience.FailoverProperties;
import com.switchboard.gateway.resilience.ResilientCaller;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Failover between models (FR-11), with a fake provider whose behaviour depends on the model name. */
class FailoverServiceTest {

	private static final OllamaProperties PROPS = new OllamaProperties("http://unused", "llama3.2", Duration.ofSeconds(5));
	private static final List<ChatMessage> MESSAGES = List.of(new ChatMessage("user", "Hi"));

	/** Fails for models listed in {@code failures}; answers for every other model. Records the call order. */
	static class ByModelProvider implements LlmProvider {
		final Map<String, ProviderException> failures;
		final List<String> calls = new CopyOnWriteArrayList<>();

		ByModelProvider(Map<String, ProviderException> failures) {
			this.failures = failures;
		}

		@Override
		public String name() {
			return "ollama";
		}

		@Override
		public Mono<ProviderResponse> chat(ProviderRequest r) {
			calls.add(r.model());
			ProviderException f = failures.get(r.model());
			return f != null ? Mono.error(f) : Mono.just(new ProviderResponse(r.model(), "from " + r.model(), "stop", 1, 1));
		}
	}

	/** Allows only the listed models as failover targets. */
	static class AllowList implements UsagePolicy {
		final List<String> allowed;

		AllowList(List<String> allowed) {
			this.allowed = allowed;
		}

		@Override
		public Mono<String> check(RequestContext c, String model, boolean auto) {
			return Mono.just(model);
		}

		@Override
		public Mono<List<String>> allowedAmong(RequestContext c, List<String> models) {
			return Mono.just(models.stream().filter(allowed::contains).toList());
		}

		@Override
		public void recordSpend(RequestContext c, BigDecimal cost) {
		}
	}

	private final ChatCompletionServiceTest.CapturingLogger logger = new ChatCompletionServiceTest.CapturingLogger();

	private ChatCompletionService service(LlmProvider provider, UsagePolicy policy, int attempts, List<String> fallbacks) {
		var caller = new ResilientCaller(new FailoverProperties(attempts, Duration.ZERO, List.of(), 1000, 50,
				Duration.ofSeconds(30)));
		return new ChatCompletionService(ProviderRegistry.single(provider), PROPS, logger, policy, caller, fallbacks,
				Clock.systemUTC());
	}

	private static ProviderException fail(Kind kind) {
		return new ProviderException("ollama", "qwen is " + kind, null, kind);
	}

	private Mono<com.switchboard.gateway.api.dto.ChatCompletionResponse> ask(ChatCompletionService s, String model) {
		return s.complete(new ChatCompletionRequest(model, MESSAGES), new RequestContext(java.util.UUID.randomUUID(), null));
	}

	@Test
	void transientFailure_failsOverToFallback_andSaysSo() {
		var p = new ByModelProvider(Map.of("qwen2.5:7b", fail(Kind.TRANSIENT)));
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 2, List.of("llama3.2")), "qwen2.5:7b"))
				.assertNext(r -> {
					assertThat(r.model()).isEqualTo("llama3.2");
					assertThat(r.choices().get(0).message().content()).isEqualTo("from llama3.2");
					assertThat(r.switchboard().modelUsed()).isEqualTo("llama3.2");
					assertThat(r.switchboard().fallbackFrom()).isEqualTo("qwen2.5:7b");
				})
				.verifyComplete();
		// qwen tried twice (retry), then llama once.
		assertThat(p.calls).containsExactly("qwen2.5:7b", "qwen2.5:7b", "llama3.2");
		assertThat(logger.entries).singleElement().satisfies(l -> {
			assertThat(l.status()).isEqualTo(RequestLog.Status.SUCCESS);
			assertThat(l.model()).isEqualTo("llama3.2");
		});
	}

	@Test
	void timeout_failsOverWithoutRetrying() {
		var p = new ByModelProvider(Map.of("qwen2.5:7b", fail(Kind.TIMEOUT)));
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 3, List.of("llama3.2")), "qwen2.5:7b"))
				.assertNext(r -> assertThat(r.model()).isEqualTo("llama3.2")).verifyComplete();
		assertThat(p.calls).containsExactly("qwen2.5:7b", "llama3.2");
	}

	@Test
	void permanentFailure_doesNotFailOver() {
		var p = new ByModelProvider(Map.of("qwen2.5:7b", fail(Kind.PERMANENT)));
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 3, List.of("llama3.2")), "qwen2.5:7b"))
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("PERMANENT")).verify();
		assertThat(p.calls).containsExactly("qwen2.5:7b");
	}

	@Test
	void success_firstTime_hasNoFallbackFrom() {
		var p = new ByModelProvider(Map.of());
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 2, List.of("llama3.2")), "qwen2.5:7b"))
				.assertNext(r -> {
					assertThat(r.model()).isEqualTo("qwen2.5:7b");
					assertThat(r.switchboard().fallbackFrom()).isNull();
				})
				.verifyComplete();
		assertThat(p.calls).containsExactly("qwen2.5:7b");
	}

	@Test
	void fallbackNotAllowedForMember_isSkipped_andErrorReturned() {
		var p = new ByModelProvider(Map.of("qwen2.5:7b", fail(Kind.TRANSIENT)));
		var onlyQwen = new AllowList(List.of("qwen2.5:7b"));
		StepVerifier.create(ask(service(p, onlyQwen, 1, List.of("llama3.2")), "qwen2.5:7b"))
				.expectError(ProviderException.class).verify();
		assertThat(p.calls).containsExactly("qwen2.5:7b"); // llama never tried
	}

	@Test
	void fallbacks_triedInOrder_skippingFailures() {
		var p = new ByModelProvider(Map.of("a", fail(Kind.TRANSIENT), "b", fail(Kind.TRANSIENT)));
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 1, List.of("b", "c", "d")), "a"))
				.assertNext(r -> {
					assertThat(r.model()).isEqualTo("c");
					assertThat(r.switchboard().fallbackFrom()).isEqualTo("a");
				})
				.verifyComplete();
		assertThat(p.calls).containsExactly("a", "b", "c");
	}

	@Test
	void allFail_returnsLastError_andLogsOneErrorRowForRequestedModel() {
		var p = new ByModelProvider(Map.of("qwen2.5:7b", fail(Kind.TRANSIENT),
				"llama3.2", new ProviderException("ollama", "llama down too", null, Kind.TRANSIENT)));
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 1, List.of("llama3.2")), "qwen2.5:7b"))
				.expectErrorSatisfies(e -> assertThat(e).hasMessage("llama down too")).verify();
		assertThat(logger.entries).singleElement().satisfies(l -> {
			assertThat(l.status()).isEqualTo(RequestLog.Status.ERROR);
			assertThat(l.model()).isEqualTo("qwen2.5:7b");
			assertThat(l.errorCode()).isEqualTo("ollama_unavailable");
		});
	}

	@Test
	void requestedModelIsAlsoAFallback_notTriedTwice() {
		var p = new ByModelProvider(Map.of("llama3.2", fail(Kind.TRANSIENT)));
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 1, List.of("llama3.2")), "llama3.2"))
				.expectError(ProviderException.class).verify();
		assertThat(p.calls).containsExactly("llama3.2");
	}

	@Test
	void noFallbacksConfigured_justRetries() {
		var p = new ByModelProvider(Map.of("qwen2.5:7b", fail(Kind.TRANSIENT)));
		StepVerifier.create(ask(service(p, UsagePolicy.NONE, 2, List.of()), "qwen2.5:7b"))
				.expectError(ProviderException.class).verify();
		assertThat(p.calls).containsExactly("qwen2.5:7b", "qwen2.5:7b");
	}
}
