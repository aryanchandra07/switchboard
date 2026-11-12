package com.switchboard.gateway.provider;

/**
 * The request can't be sent to this provider because of something the client asked for
 * (e.g. max_tokens too large, a model the gateway has no price for). Returned as 400, not 502,
 * and never retried or failed over: another attempt would fail the same way.
 */
public class InvalidProviderRequestException extends ProviderException {

	public InvalidProviderRequestException(String provider, String message) {
		super(provider, message, null, Kind.PERMANENT);
	}
}
