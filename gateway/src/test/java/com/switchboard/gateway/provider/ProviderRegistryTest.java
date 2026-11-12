package com.switchboard.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.provider.anthropic.AnthropicProvider;
import com.switchboard.gateway.provider.mock.MockProperties;
import com.switchboard.gateway.provider.mock.MockProvider;
import com.switchboard.gateway.provider.ollama.OllamaProvider;

class ProviderRegistryTest {

	private final OllamaProvider ollama = mock(OllamaProvider.class);
	private final AnthropicProvider anthropic = mock(AnthropicProvider.class);
	private final MockProperties enabled = new MockProperties(true, Duration.ZERO, 0);
	private final MockProvider mockProvider = new MockProvider(enabled);

	private ProviderRegistry registry() {
		return new ProviderRegistry(ollama, anthropic, mockProvider, enabled);
	}

	@Test
	void mockModels_goToMockProvider() {
		assertThat(registry().forModel("mock")).isSameAs(mockProvider);
		assertThat(registry().forModel("mock-fast")).isSameAs(mockProvider);
		assertThat(registry().forModel("mock-slow-1")).isSameAs(mockProvider);
	}

	@Test
	void claudeModels_goToAnthropic() {
		assertThat(registry().forModel("claude-haiku-5-5")).isSameAs(anthropic);
		assertThat(registry().forModel("claude-opus-5-5")).isSameAs(anthropic);
		assertThat(registry().forModel("claude-anything")).isSameAs(anthropic);
		assertThat(registry().forModel("claude")).isSameAs(ollama);   // needs "claude-"
		assertThat(registry().forModel("Claude-opus")).isSameAs(ollama); // exact, lower-case
	}

	@Test
	void everythingElse_goesToOllama() {
		assertThat(registry().forModel("llama3.2")).isSameAs(ollama);
		assertThat(registry().forModel("qwen2.5:7b")).isSameAs(ollama);
		assertThat(registry().forModel("mockingbird")).isSameAs(ollama);
		assertThat(registry().forModel("Mock")).isSameAs(ollama);
		assertThat(registry().forModel(null)).isSameAs(ollama);
	}

	@Test
	void disabledMock_isNotUsed() {
		ProviderRegistry r = new ProviderRegistry(ollama, anthropic, mockProvider,
				new MockProperties(false, Duration.ZERO, 0));
		assertThat(r.forModel("mock")).isSameAs(ollama);
		assertThat(r.all()).containsExactly(ollama, anthropic);
	}

	@Test
	void all_listsActiveProviders() {
		assertThat(registry().all()).containsExactly(ollama, anthropic, mockProvider);
	}

	@Test
	void single_sendsEverythingToOneProvider() {
		ProviderRegistry r = ProviderRegistry.single(ollama);
		assertThat(r.forModel("mock")).isSameAs(ollama);
		assertThat(r.forModel("claude-opus-5-5")).isSameAs(ollama);
		assertThat(r.all()).containsExactly(ollama);
	}
}
