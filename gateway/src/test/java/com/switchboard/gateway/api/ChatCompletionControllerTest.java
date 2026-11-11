package com.switchboard.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.switchboard.gateway.TestcontainersConfiguration;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;

import reactor.core.publisher.Mono;

/**
 * Calls POST /v1/chat/completions through the full Spring WebFlux stack (JSON parsing,
 * validation, error handling), with the model provider replaced by a Mockito mock.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ChatCompletionControllerTest {

	private static final String URL = "/v1/chat/completions";

	@Autowired
	private ApplicationContext context;

	@MockitoBean
	private LlmProvider provider;

	private WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).build();
		when(provider.name()).thenReturn("ollama");
		when(provider.chat(any())).thenReturn(
				Mono.just(new ProviderResponse("llama3.2", "Hello from the model", "stop", 11, 4)));
	}

	private WebTestClient.ResponseSpec post(String json) {
		return client.post().uri(URL).contentType(MediaType.APPLICATION_JSON).bodyValue(json).exchange();
	}

	// ---------- success ----------

	@Test
	void validRequest_returns200WithOpenAiShapeAndMetadata() {
		post("""
				{"model":"auto","messages":[{"role":"user","content":"Say hello"}]}
				""")
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
				.expectBody()
				.jsonPath("$.id").value(id -> assertThat((String) id).startsWith("chatcmpl-"))
				.jsonPath("$.object").isEqualTo("chat.completion")
				.jsonPath("$.created").isNumber()
				.jsonPath("$.model").isEqualTo("llama3.2")
				.jsonPath("$.choices.length()").isEqualTo(1)
				.jsonPath("$.choices[0].index").isEqualTo(0)
				.jsonPath("$.choices[0].message.role").isEqualTo("assistant")
				.jsonPath("$.choices[0].message.content").isEqualTo("Hello from the model")
				.jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
				.jsonPath("$.usage.prompt_tokens").isEqualTo(11)
				.jsonPath("$.usage.completion_tokens").isEqualTo(4)
				.jsonPath("$.usage.total_tokens").isEqualTo(15)
				.jsonPath("$.switchboard.provider").isEqualTo("ollama")
				.jsonPath("$.switchboard.model_used").isEqualTo("llama3.2")
				.jsonPath("$.switchboard.router_score").doesNotExist()
				.jsonPath("$.switchboard.cost_usd").isEqualTo(0)
				.jsonPath("$.switchboard.latency_ms").isNumber()
				.jsonPath("$.switchboard.cache_hit").isEqualTo(false);
	}

	@Test
	void autoModel_isResolvedToDefaultAndAllMessagesForwarded() {
		post("""
				{"model":"auto","messages":[
				  {"role":"system","content":"Be brief."},
				  {"role":"user","content":"Hi"}]}
				""").expectStatus().isOk();

		ArgumentCaptor<ProviderRequest> captor = ArgumentCaptor.forClass(ProviderRequest.class);
		verify(provider).chat(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo("llama3.2");
		assertThat(captor.getValue().messages()).containsExactly(
				new ChatMessage("system", "Be brief."), new ChatMessage("user", "Hi"));
	}

	@Test
	void missingModel_isTreatedAsAuto() {
		post("""
				{"messages":[{"role":"user","content":"Hi"}]}
				""").expectStatus().isOk();

		ArgumentCaptor<ProviderRequest> captor = ArgumentCaptor.forClass(ProviderRequest.class);
		verify(provider).chat(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo("llama3.2");
	}

	@Test
	void namedModel_isPassedToProvider() {
		post("""
				{"model":"qwen2.5:7b","messages":[{"role":"user","content":"Hi"}]}
				""").expectStatus().isOk();

		ArgumentCaptor<ProviderRequest> captor = ArgumentCaptor.forClass(ProviderRequest.class);
		verify(provider).chat(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo("qwen2.5:7b");
	}

	@Test
	void unknownExtraFields_areIgnored() {
		// OpenAI clients often send extra fields like temperature; they must not break the request.
		post("""
				{"model":"auto","temperature":0.2,"max_tokens":50,
				 "messages":[{"role":"user","content":"Hi","name":"bob"}]}
				""").expectStatus().isOk();
	}

	// ---------- validation errors (400) ----------

	@Test
	void emptyMessages_returns400() {
		post("{\"model\":\"auto\",\"messages\":[]}")
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.type").isEqualTo("invalid_request_error")
				.jsonPath("$.error.code").isEqualTo("invalid_body")
				.jsonPath("$.error.message").value(m -> assertThat((String) m).contains("messages"));
		verify(provider, never()).chat(any());
	}

	@Test
	void missingMessages_returns400() {
		post("{\"model\":\"auto\"}")
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.message").value(m -> assertThat((String) m).contains("messages"));
		verify(provider, never()).chat(any());
	}

	@Test
	void blankRole_returns400() {
		post("{\"messages\":[{\"role\":\"\",\"content\":\"Hi\"}]}")
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.message").value(m -> assertThat((String) m).contains("role"));
		verify(provider, never()).chat(any());
	}

	@Test
	void missingContent_returns400() {
		post("{\"messages\":[{\"role\":\"user\"}]}")
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.message").value(m -> assertThat((String) m).contains("content"));
		verify(provider, never()).chat(any());
	}

	@Test
	void invalidJson_returns400() {
		post("{this is not json")
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.type").isEqualTo("invalid_request_error")
				.jsonPath("$.error.code").isEqualTo("invalid_json");
		verify(provider, never()).chat(any());
	}

	@Test
	void emptyBody_returns400() {
		client.post().uri(URL).contentType(MediaType.APPLICATION_JSON).exchange()
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error.code").isEqualTo("invalid_json");
		verify(provider, never()).chat(any());
	}

	@Test
	void wrongContentType_returns415() {
		client.post().uri(URL).contentType(MediaType.TEXT_PLAIN).bodyValue("hello").exchange()
				.expectStatus().isEqualTo(415)
				.expectBody().jsonPath("$.error.type").isEqualTo("invalid_request_error");
	}

	@Test
	void getInsteadOfPost_returns405() {
		client.get().uri(URL).exchange().expectStatus().isEqualTo(405);
	}

	// ---------- provider errors ----------

	@Test
	void providerFails_returns502WithOpenAiStyleError() {
		when(provider.chat(any())).thenReturn(Mono.error(
				new ProviderException("ollama", "Could not reach Ollama at http://localhost:11434", null)));

		post("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}")
				.expectStatus().isEqualTo(502)
				.expectBody()
				.jsonPath("$.error.message").isEqualTo("Could not reach Ollama at http://localhost:11434")
				.jsonPath("$.error.type").isEqualTo("provider_error")
				.jsonPath("$.error.code").isEqualTo("ollama_unavailable");
	}

	@Test
	void unexpectedError_returns500WithoutLeakingDetails() {
		when(provider.chat(any())).thenReturn(Mono.error(new IllegalStateException("secret internal detail")));

		post("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}")
				.expectStatus().isEqualTo(500)
				.expectBody()
				.jsonPath("$.error.message").isEqualTo("Internal server error")
				.jsonPath("$.error.type").isEqualTo("server_error")
				.consumeWith(r -> assertThat(new String(r.getResponseBody())).doesNotContain("secret"));
	}

	// ---------- other endpoints still work ----------

	@Test
	void healthEndpoint_isUp() {
		client.get().uri("/actuator/health").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
	}

	@Test
	void requestShape_messagesListIsUnmodifiedOrder() {
		// Order of messages matters for chat models; make sure it is preserved end to end.
		post("""
				{"messages":[
				  {"role":"user","content":"1"},
				  {"role":"assistant","content":"2"},
				  {"role":"user","content":"3"}]}
				""").expectStatus().isOk();

		ArgumentCaptor<ProviderRequest> captor = ArgumentCaptor.forClass(ProviderRequest.class);
		verify(provider).chat(captor.capture());
		assertThat(captor.getValue().messages()).extracting(ChatMessage::content)
				.isEqualTo(List.of("1", "2", "3"));
	}
}
