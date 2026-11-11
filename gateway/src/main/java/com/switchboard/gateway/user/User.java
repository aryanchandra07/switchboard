package com.switchboard.gateway.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A row in the "users" table. Spring Data R2DBC maps each field to a column
 * (camelCase → snake_case, e.g. passwordHash → password_hash).
 */
@Table("users")
public record User(
		@Id UUID id,
		String email,
		@Column("password_hash") String passwordHash,
		Role role,
		UserStatus status,
		@Column("created_at") Instant createdAt,
		@Column("updated_at") Instant updatedAt) {

	/** A new, not-yet-saved user. id stays null so the database generates it. */
	public static User newUser(String email, String passwordHash, Role role) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres precision
		return new User(null, normalizeEmail(email), passwordHash, role, UserStatus.ACTIVE, now, now);
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}

	/** Emails are compared case-insensitively, so they are always stored trimmed and lower-case. */
	public static String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		// Never print the password hash in logs.
		return "User[id=" + id + ", email=" + email + ", role=" + role + ", status=" + status + "]";
	}
}
