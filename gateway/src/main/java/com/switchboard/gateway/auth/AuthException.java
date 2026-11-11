package com.switchboard.gateway.auth;

/** Login/authentication failures, turned into 401/403 JSON errors by ApiExceptionHandler. */
public abstract sealed class AuthException extends RuntimeException
		permits AuthException.InvalidCredentials, AuthException.AccountDisabled {

	protected AuthException(String message) {
		super(message);
	}

	/** Wrong email or password. Deliberately doesn't say which. */
	public static final class InvalidCredentials extends AuthException {
		public InvalidCredentials() {
			super("Invalid email or password");
		}
	}

	/** Correct credentials, but the admin has disabled this account. */
	public static final class AccountDisabled extends AuthException {
		public AccountDisabled() {
			super("This account has been disabled");
		}
	}
}
