package com.switchboard.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import reactor.core.publisher.Mono;

/**
 * Who may call what. Login tokens are JWTs sent as "Authorization: Bearer <token>".
 * The app keeps no server-side sessions (stateless), so CSRF protection isn't needed.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	/**
	 * Rules for the model API (/v1/**): every call needs "Authorization: Bearer" with either an sb- key
	 * or a web login token. Checked first (@Order(1)); everything else falls through to the chain below.
	 */
	@Bean
	@Order(1)
	SecurityWebFilterChain apiSecurityWebFilterChain(ServerHttpSecurity http,
			MemberAuthenticationManager memberAuthenticationManager) {
		return http
				.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/v1/**"))
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
				.authorizeExchange(auth -> auth.anyExchange().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.authenticationManagerResolver(exchange -> Mono.just(memberAuthenticationManager))
						.authenticationEntryPoint(SecurityConfig::apiAuthFailure))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(SecurityConfig::apiAuthFailure)
						.accessDeniedHandler((exchange, e) -> forbidden(exchange)))
				.build();
	}

	/** Rules for everything else: web login (JWT) and admin pages. */
	@Bean
	@Order(2)
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
				.authorizeExchange(auth -> auth
						.pathMatchers(HttpMethod.POST, "/auth/login").permitAll()
						.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.pathMatchers("/admin/**").hasRole("ADMIN")
						.anyExchange().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.jwt(jwt -> jwt.jwtAuthenticationConverter(SecurityConfig::toAuthentication))
						.authenticationEntryPoint((exchange, e) -> unauthorized(exchange)) // bad/expired token
						.accessDeniedHandler((exchange, e) -> forbidden(exchange)))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((exchange, e) -> unauthorized(exchange)) // no token at all
						.accessDeniedHandler((exchange, e) -> forbidden(exchange)))       // wrong role
				.build();
	}

	/** bcrypt with Spring's default strength (10). Hashes include a random salt automatically. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JwtEncoder jwtEncoder(JwtProperties props) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(props)));
	}

	@Bean
	ReactiveJwtDecoder jwtDecoder(JwtProperties props) {
		NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(secretKey(props))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		// Checks expiry (with Spring's default 60 s clock skew) and that we issued the token.
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(JwtService.ISSUER)));
		return decoder;
	}

	private static SecretKey secretKey(JwtProperties props) {
		return new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	/** Turns the token's "role" claim into a Spring authority, e.g. ADMIN → ROLE_ADMIN. */
	private static Mono<AbstractAuthenticationToken> toAuthentication(Jwt jwt) {
		String role = jwt.getClaimAsString(JwtService.ROLE_CLAIM);
		var authorities = role == null ? List.<SimpleGrantedAuthority>of()
				: List.of(new SimpleGrantedAuthority("ROLE_" + role));
		return Mono.just(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
	}

	/** Same {"error": {...}} shape as the rest of the API. */
	private static Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message,
			String type, String code) {
		var response = exchange.getResponse();
		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		String json = "{\"error\":{\"message\":\"" + message + "\",\"type\":\"" + type + "\",\"code\":\"" + code
				+ "\"}}";
		var buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
		return response.writeWith(Mono.just(buffer));
	}

	/** /v1 auth failures: 403 for a disabled account, 401 for a missing, unknown or revoked key/token. */
	private static Mono<Void> apiAuthFailure(ServerWebExchange exchange, AuthenticationException e) {
		if (e instanceof DisabledException) {
			return writeError(exchange, HttpStatus.FORBIDDEN, "This account has been disabled",
					"permission_error", "account_disabled");
		}
		return writeError(exchange, HttpStatus.UNAUTHORIZED,
				"Missing, invalid or revoked API key. Send \\\"Authorization: Bearer sb-...\\\"",
				"authentication_error", "invalid_api_key");
	}

	private static Mono<Void> unauthorized(ServerWebExchange exchange) {
		return writeError(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid login token",
				"authentication_error", "invalid_token");
	}

	private static Mono<Void> forbidden(ServerWebExchange exchange) {
		return writeError(exchange, HttpStatus.FORBIDDEN, "You do not have access to this resource",
				"permission_error", "forbidden");
	}
}
