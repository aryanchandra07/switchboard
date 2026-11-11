package com.switchboard.gateway.provider;

/** Thrown when a provider is unreachable, times out or returns an error. */
public class ProviderException extends RuntimeException {

	private final String provider;

	public ProviderException(String provider, String message, Throwable cause) {
		super(message, cause);
		this.provider = provider;
	}

	public String provider() {
		return provider;
	}
}
