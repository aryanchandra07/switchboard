package com.switchboard.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.InvalidProviderRequestException;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderException.Kind;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResilientCallerTest {

	private static final ProviderRequest REQUEST = new ProviderRequest("m", List.of(new ChatMessage("user", "hi")));
	private static final ProviderResponse OK = new ProviderResponse("m", "ok", "stop", 1, 1);

	/** Answers with a scripted sequence of outcomes and counts calls. */
	static class ScriptedProvider implements LlmProvider {
		final String name;
		final Deque<Object> script = new ArrayDeque<>();
		final AtomicInteger calls = new AtomicInteger();

		ScriptedProvider(String name, Object... outcomes) {
			this.name = name;
			script.addAll(List.of(outcomes));
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public Mono<ProviderResponse> chat(ProviderRequest request) {
			calls.incrementAndGet();
			Object next = script.isEmpty() ? OK : script.poll();
			return next instanceof RuntimeException e ? Mono.error(e) : Mono.just((ProviderResponse) next);
		}
	}

	private static ProviderException fail(Kind kind) {
		return new ProviderException("p", "failed " + kind, null, kind);
	}

	private static ResilientCaller caller(int attempts, int window, Duration openFor) {
		return new ResilientCaller(new FailoverProperties(attempts, Duration.ZERO, List.of(), window, 50, openFor));
	}

	// ---------- retry ----------

	@Test
	void transientFailure_isRetried_thenSucceeds() {
		ScriptedProvider p = new ScriptedProvider("p", fail(Kind.TRANSIENT), OK);
		StepVerifier.create(caller(2, 100, Duration.ofSeconds(30)).call(p, REQUEST)).expectNext(OK).verifyComplete();
		assertThat(p.calls).hasValue(2);
	}

	@Test
	void retries_stopAtMaxAttempts() {
		ScriptedProvider p = new ScriptedProvider("p", fail(Kind.TRANSIENT), fail(Kind.TRANSIENT), fail(Kind.TRANSIENT));
		StepVerifier.create(caller(3, 100, Duration.ofSeconds(30)).call(p, REQUEST))
				.expectErrorSatisfies(e -> assertThat(((ProviderException) e).kind()).isEqualTo(Kind.TRANSIENT))
				.verify();
		assertThat(p.calls).hasValue(3);
	}

	@Test
	void timeout_isNotRetried() {
		ScriptedProvider p = new ScriptedProvider("p", fail(Kind.TIMEOUT), OK);
		StepVerifier.create(caller(3, 100, Duration.ofSeconds(30)).call(p, REQUEST))
				.expectError(ProviderException.class).verify();
		assertThat(p.calls).hasValue(1);
	}

	@Test
	void permanentFailure_isNotRetried() {
		ScriptedProvider p = new ScriptedProvider("p", fail(Kind.PERMANENT), OK);
		StepVerifier.create(caller(3, 100, Duration.ofSeconds(30)).call(p, REQUEST))
				.expectError(ProviderException.class).verify();
		assertThat(p.calls).hasValue(1);

		ScriptedProvider q = new ScriptedProvider("q", new InvalidProviderRequestException("q", "bad"), OK);
		StepVerifier.create(caller(3, 100, Duration.ofSeconds(30)).call(q, REQUEST))
				.expectError(InvalidProviderRequestException.class).verify();
		assertThat(q.calls).hasValue(1);
	}

	@Test
	void nonProviderError_isNotRetried() {
		ScriptedProvider p = new ScriptedProvider("p", new IllegalStateException("bug"), OK);
		StepVerifier.create(caller(3, 100, Duration.ofSeconds(30)).call(p, REQUEST))
				.expectError(IllegalStateException.class).verify();
		assertThat(p.calls).hasValue(1);
	}

	@Test
	void eachCall_startsFresh() {
		// Lazy: nothing is called until subscribed, and a second call is a new attempt.
		ScriptedProvider p = new ScriptedProvider("p");
		ResilientCaller c = caller(1, 100, Duration.ofSeconds(30));
		Mono<ProviderResponse> pending = c.call(p, REQUEST);
		assertThat(p.calls).hasValue(0);
		pending.block();
		c.call(p, REQUEST).block();
		assertThat(p.calls).hasValue(2);
	}

	// ---------- circuit breaker ----------

	@Test
	void breaker_opensAfterRepeatedFailures_andFailsFastWithoutCalling() {
		ResilientCaller c = caller(1, 4, Duration.ofSeconds(30));
		ScriptedProvider p = new ScriptedProvider("down",
				fail(Kind.TRANSIENT), fail(Kind.TRANSIENT), fail(Kind.TIMEOUT), fail(Kind.TRANSIENT));
		for (int i = 0; i < 4; i++) {
			StepVerifier.create(c.call(p, REQUEST)).expectError().verify();
		}
		assertThat(c.breakerState("down")).isEqualTo(CircuitBreaker.State.OPEN);

		int before = p.calls.get();
		StepVerifier.create(c.call(p, REQUEST))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(ProviderException.class)
							.hasMessageContaining("temporarily unavailable");
					assertThat(((ProviderException) e).canFailOver()).isTrue();
				})
				.verify();
		assertThat(p.calls).hasValue(before); // not called while open
	}

	@Test
	void breaker_doesNotOpenOnClientMistakes() {
		ResilientCaller c = caller(1, 4, Duration.ofSeconds(30));
		ScriptedProvider p = new ScriptedProvider("p",
				fail(Kind.PERMANENT), fail(Kind.PERMANENT), fail(Kind.PERMANENT), fail(Kind.PERMANENT), OK);
		for (int i = 0; i < 4; i++) {
			StepVerifier.create(c.call(p, REQUEST)).expectError().verify();
		}
		assertThat(c.breakerState("p")).isEqualTo(CircuitBreaker.State.CLOSED);
		StepVerifier.create(c.call(p, REQUEST)).expectNext(OK).verifyComplete();
	}

	@Test
	void breaker_isPerProvider() {
		ResilientCaller c = caller(1, 2, Duration.ofSeconds(30));
		ScriptedProvider bad = new ScriptedProvider("bad", fail(Kind.TRANSIENT), fail(Kind.TRANSIENT));
		c.call(bad, REQUEST).onErrorResume(e -> Mono.empty()).block();
		c.call(bad, REQUEST).onErrorResume(e -> Mono.empty()).block();
		assertThat(c.breakerState("bad")).isEqualTo(CircuitBreaker.State.OPEN);

		ScriptedProvider good = new ScriptedProvider("good");
		StepVerifier.create(c.call(good, REQUEST)).expectNext(OK).verifyComplete();
	}

	@Test
	void breaker_letsTestCallThroughAfterWait_andClosesIfItWorks() throws InterruptedException {
		ResilientCaller c = caller(1, 2, Duration.ofMillis(200));
		ScriptedProvider p = new ScriptedProvider("p", fail(Kind.TRANSIENT), fail(Kind.TRANSIENT), OK);
		c.call(p, REQUEST).onErrorResume(e -> Mono.empty()).block();
		c.call(p, REQUEST).onErrorResume(e -> Mono.empty()).block();
		assertThat(c.breakerState("p")).isEqualTo(CircuitBreaker.State.OPEN);

		Thread.sleep(300);
		StepVerifier.create(c.call(p, REQUEST)).expectNext(OK).verifyComplete();
		assertThat(c.breakerState("p")).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void openBreaker_stopsFurtherRetries() {
		// Window of 2: the 2nd failed attempt opens the breaker, so the 3rd retry attempt is refused.
		ResilientCaller c = caller(5, 2, Duration.ofSeconds(30));
		ScriptedProvider p = new ScriptedProvider("p",
				fail(Kind.TRANSIENT), fail(Kind.TRANSIENT), fail(Kind.TRANSIENT), fail(Kind.TRANSIENT));
		StepVerifier.create(c.call(p, REQUEST)).expectError().verify();
		assertThat(p.calls).hasValue(2);
	}

	// ---------- settings ----------

	@Test
	void invalidSettings_areRejected() {
		assertThatThrownBy(() -> new FailoverProperties(0, Duration.ZERO, null, 10, 50, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new FailoverProperties(1, Duration.ofMillis(-1), null, 10, 50, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new FailoverProperties(1, Duration.ZERO, null, 0, 50, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new FailoverProperties(1, Duration.ZERO, null, 10, 101, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class);
		assertThat(new FailoverProperties(1, Duration.ZERO, null, 10, 50, Duration.ofSeconds(1)).fallbackModels())
				.isEmpty();
	}
}
