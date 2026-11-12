package com.switchboard.gateway.provider.anthropic;

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
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.InvalidProviderRequestException;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.providerkey.ProviderKeyService;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The Claude adapter against a fake Anthropic API (MockWebServer): no real key or network needed. */
class AnthropicProviderTest {

	private static final String KEY = "sk-ant-test-key-0001";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Map<String, AnthropicProperties.Price> PRICES = Map.of(
			"claude-haiku-5-5", new AnthropicProperties.Price(new BigDecimal("0.10"), new BigDecimal("0.50")),
			"claude-opus-5-5", new AnthropicProperties.Price(new BigDecimal("4.00"), new BigDecimal("20.00")));

	private MockWebServer server;
	private ProviderKeyService keys;
	private AnthropicProvider provider;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		keys = mock(ProviderKeyService.class);
		when(keys.decryptedKey(anyString())).thenReturn(Mono.just(KEY));
		provider = providerWithTimeout(Duration.ofSeconds(5));
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	private AnthropicProvider providerWithTimeout(Duration timeout) {
		String base = server.url("/").toString().replaceAll("/$", "");
		return new AnthropicProvider(keys, new AnthropicProperties(true, base, timeout, 16000, 16000, PRICES));
	}

	private AnthropicProvider disabledProvider() {
		String base = server.url("/").toString().replaceAll("/$", "");
		return new AnthropicProvider(keys,
				new AnthropicProperties(false, base, Duration.ofSeconds(5), 16000, 16000, PRICES));
	}

	// ---------- switched off (the default) ----------

	@Test
	void disabled_refusesClaude_withoutContactingAnthropic_evenWithKeyStored() {
		StepVerifier.create(disabledProvider().chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(InvalidProviderRequestException.class)
						.hasMessageContaining("switched off").hasMessageContaining("llama3.2"))
				.verify();
		assertThat(server.getRequestCount()).isZero();
		org.mockito.Mockito.verifyNoInteractions(keys); // the key isn't even decrypted
	}

	@Test
	void disabled_listsNoModels() {
		StepVerifier.create(disabledProvider().listModels()).verifyComplete();
	}

	private static ProviderRequest request(String model, ChatMessage... messages) {
		return new ProviderRequest(model, List.of(messages));
	}

	private static ChatMessage user(String text) {
		return new ChatMessage("user", text);
	}

	private void reply(String model, String contentJson, String stopReason, int in, int out) {
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
				{"id":"msg_01","type":"message","role":"assistant","model":"%s","content":%s,
				 "stop_reason":"%s","stop_sequence":null,"usage":{"input_tokens":%d,"output_tokens":%d}}
				""".formatted(model, contentJson, stopReason, in, out)));
	}

	private void error(int status, String type, String message) {
		server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
				.setBody("{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}"));
	}

	private JsonNode sentBody(RecordedRequest r) throws IOException {
		return JSON.readTree(r.getBody().readUtf8());
	}

	// ---------- success ----------

	@Test
	void reply_isMappedWithTokensAndCost() {
		reply("claude-haiku-5-5", "[{\"type\":\"text\",\"text\":\"Hello!\"}]", "end_turn", 1200, 300);

		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi"))))
				.assertNext(r -> {
					assertThat(r.model()).isEqualTo("claude-haiku-5-5");
					assertThat(r.content()).isEqualTo("Hello!");
					assertThat(r.finishReason()).isEqualTo("stop");
					assertThat(r.promptTokens()).isEqualTo(1200);
					assertThat(r.completionTokens()).isEqualTo(300);
					// 1200 × $0.10/1M + 300 × $0.50/1M = $0.00012 + $0.00015
					assertThat(r.costUsd()).isEqualByComparingTo("0.000270");
				})
				.verifyComplete();
	}

	@Test
	void request_hasKeyVersionHeaderAndCorrectBody() throws Exception {
		reply("claude-opus-5-5", "[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn", 1, 1);

		provider.chat(request("claude-opus-5-5",
				new ChatMessage("system", "Be brief."),
				user("Hi"),
				new ChatMessage("assistant", "Hello."),
				new ChatMessage("system", "Answer in English."),
				user("How are you?"))).block();

		RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(sent.getMethod()).isEqualTo("POST");
		assertThat(sent.getPath()).isEqualTo("/v1/messages");
		// SDK 3.x sends the key in the Authorization header, plus the API version header.
		assertThat(sent.getHeader("Authorization")).contains(KEY);
		assertThat(sent.getHeader("anthropic-version")).isNotBlank();

		JsonNode body = sentBody(sent);
		assertThat(body.get("model").asText()).isEqualTo("claude-opus-5-5");
		assertThat(body.get("max_tokens").asInt()).isEqualTo(16000); // default
		assertThat(body.get("system").asText()).isEqualTo("Be brief.\n\nAnswer in English.");
		JsonNode msgs = body.get("messages");
		assertThat(msgs).hasSize(3);
		assertThat(msgs.get(0).get("role").asText()).isEqualTo("user");
		assertThat(msgs.get(1).get("role").asText()).isEqualTo("assistant");
		assertThat(msgs.get(2).get("role").asText()).isEqualTo("user");
		assertThat(body.toString()).contains("How are you?").doesNotContain("\"role\":\"system\"");
		assertThat(body.has("temperature")).isFalse();
		assertThat(body.has("thinking")).isFalse();
	}

	@Test
	void clientMaxTokens_isPassedThrough() throws Exception {
		reply("claude-haiku-5-5", "[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn", 1, 1);
		provider.chat(new ProviderRequest("claude-haiku-5-5", List.of(user("Hi")), 256)).block();
		assertThat(sentBody(server.takeRequest(1, TimeUnit.SECONDS)).get("max_tokens").asInt()).isEqualTo(256);
	}

	@Test
	void thinkingBlocksSkipped_textBlocksJoined() {
		reply("claude-opus-5-5", """
				[{"type":"thinking","thinking":"","signature":"sig"},
				 {"type":"text","text":"Part one. "},{"type":"text","text":"Part two."}]""", "end_turn", 5, 5);

		StepVerifier.create(provider.chat(request("claude-opus-5-5", user("Hi"))))
				.assertNext(r -> assertThat(r.content()).isEqualTo("Part one. Part two."))
				.verifyComplete();
	}

	@Test
	void stopReasons_mapToOpenAiFinishReasons() {
		assertThat(AnthropicProvider.finishReason(null)).isEqualTo("stop");
		assertThat(AnthropicProvider.finishReason(com.anthropic.models.messages.StopReason.END_TURN)).isEqualTo("stop");
		assertThat(AnthropicProvider.finishReason(com.anthropic.models.messages.StopReason.MAX_TOKENS))
				.isEqualTo("length");
		assertThat(AnthropicProvider.finishReason(com.anthropic.models.messages.StopReason.REFUSAL))
				.isEqualTo("content_filter");
	}

	@Test
	void refusal_returnsContentFilterInsteadOfFailing() {
		reply("claude-opus-5-5", "[]", "refusal", 10, 0);
		StepVerifier.create(provider.chat(request("claude-opus-5-5", user("Hi"))))
				.assertNext(r -> {
					assertThat(r.finishReason()).isEqualTo("content_filter");
					assertThat(r.content()).isEmpty();
				})
				.verifyComplete();
	}

	@Test
	void listModels_arePricedModelsSorted() {
		StepVerifier.create(provider.listModels()).expectNext("claude-haiku-5-5", "claude-opus-5-5").verifyComplete();
	}

	// ---------- refused before calling Anthropic ----------

	@Test
	void unpricedModel_isRefusedWithoutCallingAnthropic() {
		StepVerifier.create(provider.chat(request("claude-unknown-9", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(InvalidProviderRequestException.class)
						.hasMessageContaining("claude-unknown-9").hasMessageContaining("claude-haiku-5-5"))
				.verify();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void maxTokensOutOfRange_isRefused() {
		StepVerifier.create(provider.chat(new ProviderRequest("claude-haiku-5-5", List.of(user("Hi")), 16001)))
				.expectError(InvalidProviderRequestException.class).verify();
		StepVerifier.create(provider.chat(new ProviderRequest("claude-haiku-5-5", List.of(user("Hi")), 0)))
				.expectError(InvalidProviderRequestException.class).verify();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void onlySystemMessages_orUnknownRole_areRefused() {
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", new ChatMessage("system", "x"))))
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("at least one user message")).verify();
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", new ChatMessage("tool", "x"))))
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("tool")).verify();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void noKeyStored_failsWithClearMessage() {
		when(keys.decryptedKey(anyString())).thenReturn(Mono.empty());
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(ProviderException.class)
							.isNotInstanceOf(InvalidProviderRequestException.class)
							.hasMessageContaining("PUT /admin/providers/anthropic");
					assertThat(((ProviderException) e).provider()).isEqualTo("anthropic");
				})
				.verify();
		assertThat(server.getRequestCount()).isZero();
	}

	// ---------- Anthropic errors ----------

	@Test
	void unauthorized_saysKeyRejected_withoutLeakingKey() {
		error(401, "authentication_error", "invalid x-api-key");
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ProviderException.class)
						.hasMessageContaining("rejected the stored API key").hasMessageNotContaining(KEY))
				.verify();
	}

	@Test
	void rateLimited_overloaded_serverError_networkDown() throws IOException {
		error(429, "rate_limit_error", "slow down");
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("429")).verify();

		error(529, "overloaded_error", "busy");
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("overloaded")).verify();

		error(500, "api_error", "oops");
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ProviderException.class)
						.hasMessageContaining("500")).verify();

		server.shutdown();
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("Could not reach Anthropic"))
				.verify(Duration.ofSeconds(15));
	}

	@Test
	void sdkDoesNotRetry_gatewayOwnsRetries() {
		error(500, "api_error", "oops");
		reply("claude-haiku-5-5", "[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn", 1, 1);
		StepVerifier.create(provider.chat(request("claude-haiku-5-5", user("Hi")))).expectError().verify();
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void slowReply_timesOut() {
		AnthropicProvider quick = providerWithTimeout(Duration.ofMillis(500));
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
				.setHeadersDelay(3, TimeUnit.SECONDS));
		StepVerifier.create(quick.chat(request("claude-haiku-5-5", user("Hi"))))
				.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ProviderException.class))
				.verify(Duration.ofSeconds(10));
	}

	// ---------- key rotation ----------

	@Test
	void rotatedKey_isUsedOnNextCall() throws Exception {
		reply("claude-haiku-5-5", "[{\"type\":\"text\",\"text\":\"a\"}]", "end_turn", 1, 1);
		reply("claude-haiku-5-5", "[{\"type\":\"text\",\"text\":\"b\"}]", "end_turn", 1, 1);

		provider.chat(request("claude-haiku-5-5", user("Hi"))).block();
		when(keys.decryptedKey(anyString())).thenReturn(Mono.just("sk-ant-rotated-key-0002"));
		provider.chat(request("claude-haiku-5-5", user("Hi"))).block();

		assertThat(server.takeRequest().getHeader("Authorization")).contains(KEY);
		assertThat(server.takeRequest().getHeader("Authorization")).contains("sk-ant-rotated-key-0002");
	}

	// ---------- pricing ----------

	@Test
	void price_costIsExactToMicroDollars() {
		var opus = PRICES.get("claude-opus-5-5");
		assertThat(opus.cost(1_000_000, 0)).isEqualByComparingTo("4.00");
		assertThat(opus.cost(0, 1_000_000)).isEqualByComparingTo("20.00");
		assertThat(opus.cost(1, 1)).isEqualByComparingTo("0.000024");
		assertThat(PRICES.get("claude-haiku-5-5").cost(1, 0)).isEqualByComparingTo("0.000000"); // $0.0000001 rounds
		assertThat(opus.cost(0, 0)).isEqualByComparingTo("0");
	}
}
