package com.switchboard.gateway.chat;

import java.util.UUID;

/**
 * Who is making a chat request. Both ids are null until member sb- API keys protect /v1
 * (a later step); the pipeline and request log already accept them.
 */
public record RequestContext(UUID userId, UUID apiKeyId) {

	public static RequestContext anonymous() {
		return new RequestContext(null, null);
	}
}
