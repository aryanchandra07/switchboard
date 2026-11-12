package com.switchboard.gateway.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;

import com.switchboard.gateway.apikey.ApiKeyService;

import reactor.core.publisher.Mono;

/**
 * Authenticates calls to /v1 from "Authorization: Bearer ...". Accepts either:
 * <ul>
 * <li>an sb- API key (code, scripts, OpenAI clients), or</li>
 * <li>the web login token (the Playground in the browser).</li>
 * </ul>
 * Both paths check the database, so revoked keys and disabled members stop working immediately (FR-03).
 */
@Component
public class MemberAuthenticationManager implements ReactiveAuthenticationManager {

	private final ApiKeyService apiKeys;
	private final ReactiveJwtDecoder jwtDecoder;
	private final AuthService authService;

	public MemberAuthenticationManager(ApiKeyService apiKeys, ReactiveJwtDecoder jwtDecoder,
			AuthService authService) {
		this.apiKeys = apiKeys;
		this.jwtDecoder = jwtDecoder;
		this.authService = authService;
	}

	@Override
	public Mono<Authentication> authenticate(Authentication authentication) {
		String token = ((BearerTokenAuthenticationToken) authentication).getToken();
		return token.startsWith(ApiKeyService.KEY_PREFIX) ? viaApiKey(token) : viaLoginToken(token);
	}

	private Mono<Authentication> viaApiKey(String key) {
		return apiKeys.verify(key)
				.<Authentication>map(k -> new MemberAuthentication(new MemberPrincipal(k.userId(), k.apiKeyId(),
						k.role())))
				.switchIfEmpty(Mono.error(new BadCredentialsException("Invalid or revoked API key")));
	}

	private Mono<Authentication> viaLoginToken(String token) {
		return jwtDecoder.decode(token)
				.onErrorMap(e -> new BadCredentialsException("Invalid login token"))
				.flatMap(jwt -> authService.currentUser(UUID.fromString(jwt.getSubject())))
				.onErrorMap(AuthException.class, e -> new DisabledException(e.getMessage()))
				.map(user -> new MemberAuthentication(new MemberPrincipal(user.id(), null, user.role().name())));
	}

	/** The authenticated caller, available to controllers as @AuthenticationPrincipal MemberPrincipal. */
	static final class MemberAuthentication extends AbstractAuthenticationToken {

		private final MemberPrincipal principal;

		MemberAuthentication(MemberPrincipal principal) {
			super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
			this.principal = principal;
			setAuthenticated(true);
		}

		@Override
		public Object getCredentials() {
			return ""; // never keep the key around
		}

		@Override
		public MemberPrincipal getPrincipal() {
			return principal;
		}
	}
}
