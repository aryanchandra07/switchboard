package com.switchboard.gateway.auth;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.switchboard.gateway.user.User;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Mono;

/** Web-UI login (POST /auth/login) and "who am I" (GET /auth/me). */
@RestController
@RequestMapping("/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request.email(), request.password())
				.map(r -> new LoginResponse(r.token().value(), "Bearer", r.token().expiresInSeconds(),
						UserView.of(r.user())));
	}

	/** Needs "Authorization: Bearer <token>". Re-checks the database, so disabled users are refused. */
	@GetMapping("/me")
	public Mono<UserView> me(@AuthenticationPrincipal Jwt jwt) {
		return authService.currentUser(UUID.fromString(jwt.getSubject())).map(UserView::of);
	}

	public record LoginRequest(
			@NotBlank @Size(max = 320) String email,
			@NotBlank @Size(max = 200) String password) {

		@Override
		public String toString() {
			return "LoginRequest[email=" + email + "]"; // never log the password
		}
	}

	public record LoginResponse(
			@JsonProperty("access_token") String accessToken,
			@JsonProperty("token_type") String tokenType,
			@JsonProperty("expires_in") long expiresIn,
			UserView user) {
	}

	/** Safe public view of a user: no password hash. */
	public record UserView(UUID id, String email, String role, String status) {
		static UserView of(User u) {
			return new UserView(u.id(), u.email(), u.role().name(), u.status().name());
		}
	}
}
