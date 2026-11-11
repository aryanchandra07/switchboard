package com.switchboard.gateway.user;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Mono;

/**
 * Database access for users. Spring Data writes the SQL from the method names,
 * e.g. findByEmail → SELECT * FROM users WHERE email = $1.
 */
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

	/** Pass an already-normalized (lower-case) email. */
	Mono<User> findByEmail(String email);

	Mono<Boolean> existsByRole(Role role);
}
