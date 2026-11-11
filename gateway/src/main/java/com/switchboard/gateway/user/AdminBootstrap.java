package com.switchboard.gateway.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Creates the first admin account on startup (FR-02: invite-only, no public sign-up).
 * Runs only when no admin exists yet, so restarting never overwrites a changed password.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
	static final int MIN_PASSWORD_LENGTH = 12;

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AdminProperties admin;

	public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AdminProperties admin) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.admin = admin;
	}

	@Override
	public void run(ApplicationArguments args) {
		createAdminIfMissing().block();
	}

	/** Returns the outcome so it can be unit-tested. */
	Mono<Outcome> createAdminIfMissing() {
		return users.existsByRole(Role.ADMIN).flatMap(adminExists -> {
			if (adminExists) {
				return Mono.just(Outcome.ALREADY_EXISTS);
			}
			if (!admin.isConfigured()) {
				log.warn("No admin account exists. Set SWITCHBOARD_ADMIN_EMAIL and SWITCHBOARD_ADMIN_PASSWORD "
						+ "to create one on startup.");
				return Mono.just(Outcome.NOT_CONFIGURED);
			}
			if (admin.password().length() < MIN_PASSWORD_LENGTH) {
				return Mono.error(new IllegalStateException(
						"SWITCHBOARD_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters"));
			}
			User user = User.newUser(admin.email(), passwordEncoder.encode(admin.password()), Role.ADMIN);
			return users.save(user).map(saved -> {
				log.info("Created first admin account {}", saved.email());
				return Outcome.CREATED;
			});
		});
	}

	enum Outcome {
		CREATED, ALREADY_EXISTS, NOT_CONFIGURED
	}
}
