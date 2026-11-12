package com.switchboard.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.ProviderException.Kind;
import com.switchboard.gateway.provider.anthropic.AnthropicProperties;
import com.switchboard.gateway.provider.anthropic.AnthropicProvider;
import com.switchboard.gateway.provider.mock.MockProperties;
import com.switchboard.gateway.provider.mock.MockProvider;
import com.switchboard.gateway.provider.ollama.OllamaProperties;
import com.switchboard.gateway.provider.ollama.OllamaProvider;
import com.switchboard.gateway.providerkey.ProviderKeyService;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import reactor.core.publisher.Mono;

/** Each provider labels its failures correctly, because retry and failover depend on the label. */
class ProviderFailureKindTest {

	private static final ProviderRequest REQ = new ProviderRequest("m", List.of(new ChatMessage("user", "hi")));

	private MockWebServer server;

	@BeforeEach
	void start() throws IOException {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void stop() throws IOException {
		server.shutdown();
	}

	private String base() {
		return server.url("/").toString().replaceAll("/$", "");
	}

	private static Kind kindOf(Mono<?> call) {
		try {
			call.block(Duration.ofSeconds(15));
			throw new AssertionError("expected a failure");
		}
		catch (ProviderException e) {
			return e.kind();
		}
	}

	private void status(int code) {
		server.enqueue(new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
				.setBody("{\"error\":\"x\",\"type\":\"error\"}"));
	}

	// ---------- Ollama ----------

	@ParameterizedTest(name = "Ollama HTTP {0} -> {1}")
	@CsvSource({ "404, PERMANENT", "400, PERMANENT", "500, TRANSIENT", "503, TRANSIENT" })
	void ollama_httpStatus(int code, Kind expected) {
		status(code);
		var ollama = new OllamaProvider(new OllamaProperties(base(), "llama3.2", Duration.ofSeconds(5)));
		assertThat(kindOf(ollama.chat(REQ))).isEqualTo(expected);
	}

	@Test
	void ollama_timeout_isTimeout() {
		server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(3, TimeUnit.SECONDS));
		var ollama = new OllamaProvider(new OllamaProperties(base(), "llama3.2", Duration.ofMillis(300)));
		assertThat(kindOf(ollama.chat(REQ))).isEqualTo(Kind.TIMEOUT);
	}

	@Test
	void ollama_unreachable_isTransient() throws IOException {
		String dead = base();
		server.shutdown();
		var ollama = new OllamaProvider(new OllamaProperties(dead, "llama3.2", Duration.ofSeconds(5)));
		assertThat(kindOf(ollama.chat(REQ))).isEqualTo(Kind.TRANSIENT);
	}

	// ---------- Anthropic ----------

	private AnthropicProvider anthropic(boolean keyStored) {
		ProviderKeyService keys = mock(ProviderKeyService.class);
		when(keys.decryptedKey(anyString())).thenReturn(keyStored ? Mono.just("sk-ant-x") : Mono.empty());
		return new AnthropicProvider(keys, new AnthropicProperties(true, base(), Duration.ofSeconds(5), 100, 100,
				Map.of("claude-haiku-5-5", new AnthropicProperties.Price(BigDecimal.ONE, BigDecimal.ONE))));
	}

	private static ProviderRequest haiku() {
		return new ProviderRequest("claude-haiku-5-5", List.of(new ChatMessage("user", "hi")));
	}

	@ParameterizedTest(name = "Anthropic HTTP {0} -> {1}")
	@CsvSource({ "401, PERMANENT", "403, PERMANENT", "400, PERMANENT", "404, PERMANENT", "429, TRANSIENT",
			"500, TRANSIENT", "529, TRANSIENT" })
	void anthropic_httpStatus(int code, Kind expected) {
		server.enqueue(new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
				.setBody("{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"x\"}}"));
		assertThat(kindOf(anthropic(true).chat(haiku()))).isEqualTo(expected);
	}

	@Test
	void anthropic_noKey_andBadRequest_arePermanent() {
		assertThat(kindOf(anthropic(false).chat(haiku()))).isEqualTo(Kind.PERMANENT);
		assertThat(kindOf(anthropic(true).chat(new ProviderRequest("claude-unpriced", REQ.messages()))))
				.isEqualTo(Kind.PERMANENT);
	}

	// ---------- Mock ----------

	@Test
	void mock_simulatedFailure_isTransient() {
		var mockProvider = new MockProvider(new MockProperties(true, Duration.ZERO, 1.0));
		assertThat(kindOf(mockProvider.chat(REQ))).isEqualTo(Kind.TRANSIENT);
	}

	@Test
	void defaultKind_isTransient_andOnlyPermanentStopsFailover() {
		assertThat(new ProviderException("p", "x", null).kind()).isEqualTo(Kind.TRANSIENT);
		assertThat(new ProviderException("p", "x", null, Kind.TIMEOUT).canFailOver()).isTrue();
		assertThat(new ProviderException("p", "x", null, Kind.PERMANENT).canFailOver()).isFalse();
		assertThat(new InvalidProviderRequestException("p", "x").canFailOver()).isFalse();
	}
}
