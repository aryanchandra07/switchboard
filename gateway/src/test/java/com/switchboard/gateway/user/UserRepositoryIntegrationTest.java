package com.switchboard.gateway.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;

import com.switchboard.gateway.TestcontainersConfiguration;

import reactor.test.StepVerifier;

/** Runs against a real Postgres (Testcontainers) with the real Flyway migrations applied. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserRepositoryIntegrationTest {

	@Autowired
	private UserRepository users;

	@Autowired
	private DatabaseClient db;

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	@Test
	void flywayMigration_wasApplied() {
		StepVerifier.create(db.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
				.map(row -> row.get("version", String.class)).all().collectList())
				.assertNext(versions -> assertThat(versions).contains("1"))
				.verifyComplete();
	}

	@Test
	void save_generatesIdAndCanBeFoundByEmailAndId() {
		String email = uniqueEmail();
		User saved = users.save(User.newUser(email, "hash", Role.MEMBER)).block();

		assertThat(saved.id()).isNotNull();
		StepVerifier.create(users.findByEmail(email))
				.assertNext(u -> {
					assertThat(u.id()).isEqualTo(saved.id());
					assertThat(u.passwordHash()).isEqualTo("hash");
					assertThat(u.role()).isEqualTo(Role.MEMBER);
					assertThat(u.status()).isEqualTo(UserStatus.ACTIVE);
					assertThat(u.createdAt()).isNotNull();
				})
				.verifyComplete();
		StepVerifier.create(users.findById(saved.id())).expectNextCount(1).verifyComplete();
	}

	@Test
	void findByEmail_unknown_isEmpty() {
		StepVerifier.create(users.findByEmail(uniqueEmail())).verifyComplete();
	}

	@Test
	void duplicateEmail_isRejectedByDatabase() {
		String email = uniqueEmail();
		users.save(User.newUser(email, "hash", Role.MEMBER)).block();

		StepVerifier.create(users.save(User.newUser(email, "other", Role.MEMBER)))
				.expectError(DataIntegrityViolationException.class).verify();
	}

	@Test
	void upperCaseEmail_isRejectedByDatabaseConstraint() {
		StepVerifier.create(db.sql("INSERT INTO users (email, password_hash, role) VALUES ('UP@Example.com','h','MEMBER')")
				.fetch().rowsUpdated())
				.expectError(DataIntegrityViolationException.class).verify();
	}

	@Test
	void invalidRoleOrStatus_isRejectedByDatabaseConstraint() {
		StepVerifier.create(db.sql("INSERT INTO users (email, password_hash, role) VALUES ('" + uniqueEmail()
				+ "','h','SUPERUSER')").fetch().rowsUpdated())
				.expectError(DataIntegrityViolationException.class).verify();
		StepVerifier.create(db.sql("INSERT INTO users (email, password_hash, role, status) VALUES ('" + uniqueEmail()
				+ "','h','MEMBER','BANNED')").fetch().rowsUpdated())
				.expectError(DataIntegrityViolationException.class).verify();
	}

	@Test
	void existsByRole_findsBootstrappedAdmin() {
		// AdminBootstrap runs at startup with the test profile's admin settings.
		StepVerifier.create(users.existsByRole(Role.ADMIN)).expectNext(true).verifyComplete();
		StepVerifier.create(users.findByEmail("admin@test.local"))
				.assertNext(u -> assertThat(u.role()).isEqualTo(Role.ADMIN))
				.verifyComplete();
	}
}
