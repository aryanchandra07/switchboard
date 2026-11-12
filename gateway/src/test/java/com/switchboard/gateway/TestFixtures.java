package com.switchboard.gateway;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.switchboard.gateway.apikey.ApiKeyService;
import com.switchboard.gateway.user.Role;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;
import com.switchboard.gateway.user.UserStatus;

/** Helpers that create real rows (users, keys) for integration tests. */
public final class TestFixtures {

	public static final String MEMBER_PASSWORD = "member-password-123";

	private TestFixtures() {
	}

	public static User createMember(UserRepository users, PasswordEncoder encoder) {
		return createUser(users, encoder, Role.MEMBER, UserStatus.ACTIVE);
	}

	public static User createUser(UserRepository users, PasswordEncoder encoder, Role role, UserStatus status) {
		User u = User.newUser("member-" + UUID.randomUUID() + "@example.com", encoder.encode(MEMBER_PASSWORD), role);
		return users.save(new User(null, u.email(), u.passwordHash(), u.role(), status, u.createdAt(),
				u.updatedAt())).block();
	}

	public static ApiKeyService.CreatedKey createKey(ApiKeyService keys, User owner) {
		return keys.create(owner.id(), "test key").block();
	}

	/** Lets a member use the models tests call by name. */
	public static void allowModels(com.switchboard.gateway.member.MemberService members, UUID userId) {
		members.limitsForUser(userId).block();
		members.update(userId, null, new com.switchboard.gateway.member.MemberService.LimitsUpdate(null, null, null,
				java.util.List.of("llama3.2", "qwen2.5:7b"))).block();
	}

	public static User withStatus(User u, UserStatus status) {
		return new User(u.id(), u.email(), u.passwordHash(), u.role(), status, u.createdAt(), u.updatedAt());
	}
}
