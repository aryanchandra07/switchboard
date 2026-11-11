package com.switchboard.gateway.auth;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Checks email + password and hands out a login token (FR-01, FR-03). */
@Service
public class AuthService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	/**
	 * Hash of a random password, used when the email doesn't exist so that the response takes
	 * the same time either way (otherwise an attacker could tell which emails are registered).
	 */
	private final String dummyHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public Mono<LoginResult> login(String email, String password) {
		String normalized = User.normalizeEmail(email);
		return users.findByEmail(normalized)
				.map(java.util.Optional::of)
				.defaultIfEmpty(java.util.Optional.empty())
				// bcrypt is deliberately slow (~100 ms), so run it off the request threads.
				.publishOn(Schedulers.boundedElastic())
				.map(found -> {
					String hash = found.map(User::passwordHash).orElse(dummyHash);
					boolean passwordOk = passwordEncoder.matches(password, hash);
					if (found.isEmpty() || !passwordOk) {
						throw new AuthException.InvalidCredentials();
					}
					User user = found.get();
					if (!user.isActive()) {
						throw new AuthException.AccountDisabled();
					}
					return new LoginResult(user, jwtService.issue(user));
				});
	}

	/** Loads the user behind a token, refusing disabled or deleted accounts. */
	public Mono<User> currentUser(UUID userId) {
		return users.findById(userId)
				.switchIfEmpty(Mono.error(new AuthException.InvalidCredentials()))
				.flatMap(u -> u.isActive() ? Mono.just(u) : Mono.error(new AuthException.AccountDisabled()));
	}

	public record LoginResult(User user, JwtService.IssuedToken token) {
	}
}
