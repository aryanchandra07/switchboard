package com.switchboard.gateway.chat;

import java.util.UUID;

/**
 * Who is making a chat request. {@code apiKeyId} is null when the web login token was used.
 * {@link #anonymous()} is only for unit tests.
 */
public record RequestContext(UUID userId, UUID apiKeyId, String role) {

	public RequestContext(UUID userId, UUID apiKeyId) {
		this(userId, apiKeyId, "MEMBER");
	}

	public static RequestContext anonymous() {
		return new RequestContext(null, null, null);
	}

	/** Members are subject to budgets and rate limits; the admin is not (bounded by the provider spend limit). */
	public boolean isLimited() {
		return userId != null && "MEMBER".equals(role);
	}
}
