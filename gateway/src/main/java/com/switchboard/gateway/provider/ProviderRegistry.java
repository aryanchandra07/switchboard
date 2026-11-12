package com.switchboard.gateway.provider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.switchboard.gateway.provider.anthropic.AnthropicProvider;
import com.switchboard.gateway.provider.mock.MockProperties;
import com.switchboard.gateway.provider.mock.MockProvider;
import com.switchboard.gateway.provider.ollama.OllamaProvider;

/**
 * Picks which provider serves a model, by its name:
 * <ul>
 * <li>"mock" or "mock-..." → Mock provider (if enabled)</li>
 * <li>"claude-..." → Anthropic</li>
 * <li>everything else → Ollama</li>
 * </ul>
 */
@Component
public class ProviderRegistry {

	private final LlmProvider defaultProvider;
	private final LlmProvider anthropic; // null in single-provider test setups
	private final LlmProvider mock;      // null when disabled

	@Autowired
	public ProviderRegistry(OllamaProvider ollama, AnthropicProvider anthropic, MockProvider mock,
			MockProperties mockProperties) {
		this(ollama, anthropic, mockProperties.enabled() ? mock : null);
	}

	private ProviderRegistry(LlmProvider defaultProvider, LlmProvider anthropic, LlmProvider mock) {
		this.defaultProvider = defaultProvider;
		this.anthropic = anthropic;
		this.mock = mock;
	}

	/** Every model goes to one provider. For unit tests. */
	public static ProviderRegistry single(LlmProvider provider) {
		return new ProviderRegistry(provider, null, null);
	}

	public LlmProvider forModel(String model) {
		if (mock != null && isMockModel(model)) {
			return mock;
		}
		if (anthropic != null && isClaudeModel(model)) {
			return anthropic;
		}
		return defaultProvider;
	}

	/** All active providers, e.g. to list their models. */
	public List<LlmProvider> all() {
		List<LlmProvider> all = new ArrayList<>();
		all.add(defaultProvider);
		if (anthropic != null) {
			all.add(anthropic);
		}
		if (mock != null) {
			all.add(mock);
		}
		return List.copyOf(all);
	}

	static boolean isMockModel(String model) {
		return model != null && (model.equals(MockProvider.NAME) || model.startsWith(MockProvider.NAME + "-"));
	}

	static boolean isClaudeModel(String model) {
		return model != null && model.startsWith("claude-");
	}
}
