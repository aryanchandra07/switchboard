package com.switchboard.gateway.provider.ollama;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings read from application.properties (prefix "switchboard.ollama").
 */
@ConfigurationProperties(prefix = "switchboard.ollama")
public record OllamaProperties(String baseUrl, String defaultModel, Duration timeout) {
}
