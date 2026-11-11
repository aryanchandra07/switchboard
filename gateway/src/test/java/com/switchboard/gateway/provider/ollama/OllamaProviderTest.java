package com.switchboard.gateway.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.test.StepVerifier;

/** Tests OllamaProvider against a fake Ollama server (MockWebServer), so real Ollama isn't needed. */
class OllamaProviderTest {

	private MockWebServer server;
	private OllamaProvider provider;

	private static final ProviderRequest REQUEST = new ProviderRequest("llama3.2",
			List.of(new ChatMessage("system", "Be brief."), new ChatMessage("user", "Hi")));

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		provider = providerFor(server.url("/").toString(), Duration.ofSeconds(5));
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	private static OllamaProvider providerFor(String baseUrl, Duration timeout) {
		return new OllamaProvider(new OllamaProperties(baseUrl, "llama3.2", timeout));
	}

	private void enqueueJson(int status, String body) {
		server.enqueue(new MockResponse().setResponseCode(status)
				.setHeader("Content-Type", "application/json").setBody(body));
	}

	@Test
	void name_isOllama() {
		assertThat(provider.name()).isEqualTo("ollama");
	}

	@Test
	void chat_mapsOllamaReplyToProviderResponse() {
		enqueueJson(200, """
				{"model":"llama3.2","created_at":"2026-11-11T10:00:00Z",
				 "message":{"role":"assistant","content":"Hello there!"},
				 "done":true,"done_reason":"stop",
				 "total_duration":123,"prompt_eval_count":12,"eval_count":5}
				""");

		StepVerifier.create(provider.chat(REQUEST))
				.assertNext(r -> {
					assertThat(r.model()).isEqualTo("llama3.2");
					assertThat(r.content()).isEqualTo("Hello there!");
					assertThat(r.finishReason()).isEqualTo("stop");
					assertThat(r.promptTokens()).isEqualTo(12);
					assertThat(r.completionTokens()).isEqualTo(5);
				})
				.verifyComplete();
	}

	@Test
	void chat_sendsCorrectRequestToOllama() throws InterruptedException {
		enqueueJson(200, """
				{"model":"llama3.2","message":{"role":"assistant","content":"ok"},"done":true}
				""");

		StepVerifier.create(provider.chat(REQUEST)).expectNextCount(1).verifyComplete();

		RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(sent).isNotNull();
		assertThat(sent.getMethod()).isEqualTo("POST");
		assertThat(sent.getPath()).isEqualTo("/api/chat");
		assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
		String body = sent.getBody().readUtf8().replace(" ", "");
		assertThat(body)
				.contains("\"model\":\"llama3.2\"")
				.contains("\"stream\":false")
				.contains("{\"role\":\"system\",\"content\":\"Bebrief.\"}")
				.contains("{\"role\":\"user\",\"content\":\"Hi\"}");
	}

	@Test
	void chat_missingOptionalFields_usesSafeDefaults() {
		enqueueJson(200, """
				{"model":"llama3.2","message":{"role":"assistant"},"done":true}
				""");

		StepVerifier.create(provider.chat(REQUEST))
				.assertNext(r -> {
					assertThat(r.content()).isEmpty();
					assertThat(r.finishReason()).isEqualTo("stop");
					assertThat(r.promptTokens()).isZero();
					assertThat(r.completionTokens()).isZero();
				})
				.verifyComplete();
	}

	@Test
	void chat_ollamaReturns404_failsWithProviderException() {
		enqueueJson(404, """
				{"error":"model \\"nope\\" not found, try pulling it first"}
				""");

		StepVerifier.create(provider.chat(new ProviderRequest("nope", REQUEST.messages())))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(ProviderException.class);
					assertThat(((ProviderException) e).provider()).isEqualTo("ollama");
					assertThat(e.getMessage()).contains("HTTP 404").contains("not found");
				})
				.verify();
	}

	@Test
	void chat_ollamaReturns500_failsWithProviderException() {
		enqueueJson(500, "{\"error\":\"out of memory\"}");

		StepVerifier.create(provider.chat(REQUEST))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(ProviderException.class);
					assertThat(e.getMessage()).contains("HTTP 500");
				})
				.verify();
	}

	@Test
	void chat_ollamaTooSlow_timesOut() {
		provider = providerFor(server.url("/").toString(), Duration.ofMillis(300));
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody("{\"model\":\"llama3.2\",\"done\":true}")
				.setHeadersDelay(3, TimeUnit.SECONDS));

		StepVerifier.create(provider.chat(REQUEST))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(ProviderException.class);
					assertThat(e.getMessage()).contains("did not respond");
				})
				.verify(Duration.ofSeconds(5));
	}

	@Test
	void listModels_readsTagsAndDropsLatestSuffix() throws InterruptedException {
		enqueueJson(200, """
				{"models":[{"name":"llama3.2:latest","size":2000},{"name":"qwen2.5:7b"},{"name":"nomic-embed-text:latest"}]}
				""");

		StepVerifier.create(provider.listModels().collectList())
				.assertNext(names -> assertThat(names).containsExactly("llama3.2", "qwen2.5:7b", "nomic-embed-text"))
				.verifyComplete();
		assertThat(server.takeRequest(1, TimeUnit.SECONDS).getPath()).isEqualTo("/api/tags");
	}

	@Test
	void listModels_noModels_isEmpty() {
		enqueueJson(200, "{\"models\":[]}");
		StepVerifier.create(provider.listModels()).verifyComplete();
	}

	@Test
	void chat_ollamaNotRunning_failsWithProviderException() throws IOException {
		// Start and stop a server to get a port that nothing is listening on.
		MockWebServer stopped = new MockWebServer();
		stopped.start();
		String deadUrl = stopped.url("/").toString();
		stopped.shutdown();
		provider = providerFor(deadUrl, Duration.ofSeconds(5));

		StepVerifier.create(provider.chat(REQUEST))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(ProviderException.class);
					assertThat(e.getMessage()).contains("Could not reach Ollama");
				})
				.verify(Duration.ofSeconds(10));
	}
}
