package com.switchboard.gateway.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AdminBootstrapTest {

	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost = fast tests

	private AdminBootstrap bootstrap(String email, String password) {
		return new AdminBootstrap(users, encoder, new AdminProperties(email, password));
	}

	@Test
	void noAdminYet_createsAdminWithHashedPasswordAndLowercaseEmail() {
		when(users.existsByRole(Role.ADMIN)).thenReturn(Mono.just(false));
		when(users.save(any())).thenAnswer(inv -> {
			User u = inv.getArgument(0);
			return Mono.just(new User(UUID.randomUUID(), u.email(), u.passwordHash(), u.role(), u.status(),
					u.createdAt(), u.updatedAt()));
		});

		StepVerifier.create(bootstrap("Root@Example.COM", "a-strong-password").createAdminIfMissing())
				.expectNext(AdminBootstrap.Outcome.CREATED).verifyComplete();

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(users).save(saved.capture());
		User u = saved.getValue();
		assertThat(u.email()).isEqualTo("root@example.com");
		assertThat(u.role()).isEqualTo(Role.ADMIN);
		assertThat(u.status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(u.passwordHash()).isNotEqualTo("a-strong-password").startsWith("$2");
		assertThat(encoder.matches("a-strong-password", u.passwordHash())).isTrue();
	}

	@Test
	void adminAlreadyExists_doesNothing() {
		when(users.existsByRole(Role.ADMIN)).thenReturn(Mono.just(true));

		StepVerifier.create(bootstrap("root@example.com", "a-strong-password").createAdminIfMissing())
				.expectNext(AdminBootstrap.Outcome.ALREADY_EXISTS).verifyComplete();
		verify(users, never()).save(any());
	}

	@Test
	void notConfigured_doesNothing() {
		when(users.existsByRole(Role.ADMIN)).thenReturn(Mono.just(false));

		StepVerifier.create(bootstrap("", "").createAdminIfMissing())
				.expectNext(AdminBootstrap.Outcome.NOT_CONFIGURED).verifyComplete();
		StepVerifier.create(bootstrap(null, null).createAdminIfMissing())
				.expectNext(AdminBootstrap.Outcome.NOT_CONFIGURED).verifyComplete();
		StepVerifier.create(bootstrap("root@example.com", "  ").createAdminIfMissing())
				.expectNext(AdminBootstrap.Outcome.NOT_CONFIGURED).verifyComplete();
		verify(users, never()).save(any());
	}

	@Test
	void passwordTooShort_failsStartupWithClearMessage() {
		when(users.existsByRole(Role.ADMIN)).thenReturn(Mono.just(false));

		StepVerifier.create(bootstrap("root@example.com", "short").createAdminIfMissing())
				.expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("at least 12"))
				.verify();
		verify(users, never()).save(any());
	}
}
