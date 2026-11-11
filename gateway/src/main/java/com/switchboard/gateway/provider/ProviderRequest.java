package com.switchboard.gateway.provider;

import java.util.List;

import com.switchboard.gateway.api.dto.ChatMessage;

/** What the gateway asks a provider: a concrete model name plus the conversation. */
public record ProviderRequest(String model, List<ChatMessage> messages) {
}
