package com.switchboard.gateway.provider;

/** Thrown when a provider is unreachable, times out or returns an error. */
public class ProviderException extends RuntimeException {

	/** What kind of failure it was, which decides what the gateway does next (FR-11). */
	public enum Kind {
		/** Might work if tried again (connection refused, 5xx, 429, overloaded): retry, then fail over. */
		TRANSIENT,
		/** No reply in time: don't retry the same slow provider, fail over straight away. */
		TIMEOUT,
		/** Will fail the same way every time (bad key, unknown model, bad request): no retry, no failover. */
		PERMANENT
	}

	private final String provider;
	private final Kind kind;

	public ProviderException(String provider, String message, Throwable cause) {
		this(provider, message, cause, Kind.TRANSIENT);
	}

	public ProviderException(String provider, String message, Throwable cause, Kind kind) {
		super(message, cause);
		this.provider = provider;
		this.kind = kind;
	}

	public String provider() {
		return provider;
	}

	public Kind kind() {
		return kind;
	}

	/** Worth trying another model for. */
	public boolean canFailOver() {
		return kind != Kind.PERMANENT;
	}
}
