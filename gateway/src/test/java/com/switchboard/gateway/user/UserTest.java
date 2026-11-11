package com.switchboard.gateway.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserTest {

	@Test
	void normalizeEmail_trimsAndLowercases() {
		assertThat(User.normalizeEmail("  Alice@Example.COM ")).isEqualTo("alice@example.com");
		assertThat(User.normalizeEmail(null)).isNull();
	}

	@Test
	void newUser_isActiveWithNormalizedEmailAndNoId() {
		User u = User.newUser("Bob@Example.com", "hash", Role.MEMBER);
		assertThat(u.id()).isNull();
		assertThat(u.email()).isEqualTo("bob@example.com");
		assertThat(u.role()).isEqualTo(Role.MEMBER);
		assertThat(u.status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(u.isActive()).isTrue();
		assertThat(u.createdAt()).isNotNull();
		assertThat(u.updatedAt()).isEqualTo(u.createdAt());
	}

	@Test
	void disabledUser_isNotActive() {
		User u = User.newUser("a@b.c", "hash", Role.MEMBER);
		User disabled = new User(u.id(), u.email(), u.passwordHash(), u.role(), UserStatus.DISABLED,
				u.createdAt(), u.updatedAt());
		assertThat(disabled.isActive()).isFalse();
	}

	@Test
	void toString_neverContainsPasswordHash() {
		User u = User.newUser("a@b.c", "$2a$10$secretHashValue", Role.ADMIN);
		assertThat(u.toString()).doesNotContain("secretHashValue").contains("a@b.c");
	}
}
