package com.switchboard.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.switchboard.gateway.user.Role;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;
import com.switchboard.gateway.user.UserStatus;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthServiceTest {

	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
	private final JwtService jwtService = mock(JwtService.class);
	private AuthService service;

	private final String hash = new BCryptPasswordEncoder(4).encode("correct-password");
	private final User active = user(UserStatus.ACTIVE);
	private final User disabled = user(UserStatus.DISABLED);

	private User user(UserStatus status) {
		return new User(UUID.randomUUID(), "alice@example.com", hash, Role.MEMBER, status, Instant.now(),
				Instant.now());
	}

	@BeforeEach
	void setUp() {
		service = new AuthService(users, encoder, jwtService);
		when(jwtService.issue(any())).thenReturn(new JwtService.IssuedToken("token-123", 3600));
		when(users.findByEmail(anyString())).thenReturn(Mono.empty());
	}

	@Test
	void correctCredentials_returnUserAndToken() {
		when(users.findByEmail("alice@example.com")).thenReturn(Mono.just(active));

		StepVerifier.create(service.login("alice@example.com", "correct-password"))
				.assertNext(r -> {
					assertThat(r.user()).isEqualTo(active);
					assertThat(r.token().value()).isEqualTo("token-123");
				})
				.verifyComplete();
	}

	@Test
	void emailIsNormalizedBeforeLookup() {
		when(users.findByEmail("alice@example.com")).thenReturn(Mono.just(active));

		StepVerifier.create(service.login("  ALICE@Example.com ", "correct-password"))
				.expectNextCount(1).verifyComplete();
		verify(users).findByEmail("alice@example.com");
	}

	@Test
	void wrongPassword_isInvalidCredentials() {
		when(users.findByEmail("alice@example.com")).thenReturn(Mono.just(active));

		StepVerifier.create(service.login("alice@example.com", "wrong-password"))
				.expectError(AuthException.InvalidCredentials.class).verify();
		verify(jwtService, never()).issue(any());
	}

	@Test
	void unknownEmail_isSameErrorAsWrongPassword_andStillChecksAHash() {
		StepVerifier.create(service.login("nobody@example.com", "whatever"))
				.expectErrorSatisfies(e -> assertThat(e)
						.isInstanceOf(AuthException.InvalidCredentials.class)
						.hasMessage("Invalid email or password"))
				.verify();
		// A bcrypt check still ran (against the dummy hash) so timing doesn't reveal unknown emails.
		verify(encoder).matches(eq("whatever"), anyString());
		verify(jwtService, never()).issue(any());
	}

	@Test
	void disabledAccount_withCorrectPassword_isAccountDisabled() {
		when(users.findByEmail("alice@example.com")).thenReturn(Mono.just(disabled));

		StepVerifier.create(service.login("alice@example.com", "correct-password"))
				.expectError(AuthException.AccountDisabled.class).verify();
		verify(jwtService, never()).issue(any());
	}

	@Test
	void disabledAccount_withWrongPassword_doesNotRevealItIsDisabled() {
		when(users.findByEmail("alice@example.com")).thenReturn(Mono.just(disabled));

		StepVerifier.create(service.login("alice@example.com", "wrong-password"))
				.expectError(AuthException.InvalidCredentials.class).verify();
	}

	@Test
	void currentUser_active_isReturned() {
		when(users.findById(active.id())).thenReturn(Mono.just(active));
		StepVerifier.create(service.currentUser(active.id())).expectNext(active).verifyComplete();
	}

	@Test
	void currentUser_disabled_isRefused() {
		when(users.findById(disabled.id())).thenReturn(Mono.just(disabled));
		StepVerifier.create(service.currentUser(disabled.id()))
				.expectError(AuthException.AccountDisabled.class).verify();
	}

	@Test
	void currentUser_deleted_isRefused() {
		UUID gone = UUID.randomUUID();
		when(users.findById(gone)).thenReturn(Mono.empty());
		StepVerifier.create(service.currentUser(gone))
				.expectError(AuthException.InvalidCredentials.class).verify();
	}
}
