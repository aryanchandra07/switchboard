package com.switchboard.gateway.member;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.switchboard.gateway.user.Role;
import com.switchboard.gateway.user.User;
import com.switchboard.gateway.user.UserRepository;
import com.switchboard.gateway.user.UserStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Admin management of members (FR-02, FR-03, FR-06). Only MEMBER accounts can be managed here,
 * so the admin can never disable or delete themselves.
 */
@Service
public class MemberService {

	private static final Logger log = LoggerFactory.getLogger(MemberService.class);

	static final int MIN_PASSWORD_LENGTH = 12;
	static final int MAX_ALLOWED_MODELS = 20;
	static final BigDecimal MAX_BUDGET = new BigDecimal("10000.00");
	private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._:/-]{1,100}");

	private final UserRepository users;
	private final MemberLimitsRepository limitsRepository;
	private final R2dbcEntityTemplate template;
	private final PasswordEncoder passwordEncoder;
	private final MemberDefaultsProperties defaults;
	private final Clock clock;

	@Autowired
	public MemberService(UserRepository users, MemberLimitsRepository limitsRepository, R2dbcEntityTemplate template,
			PasswordEncoder passwordEncoder, MemberDefaultsProperties defaults) {
		this(users, limitsRepository, template, passwordEncoder, defaults, Clock.systemUTC());
	}

	MemberService(UserRepository users, MemberLimitsRepository limitsRepository, R2dbcEntityTemplate template,
			PasswordEncoder passwordEncoder, MemberDefaultsProperties defaults, Clock clock) {
		this.users = users;
		this.limitsRepository = limitsRepository;
		this.template = template;
		this.passwordEncoder = passwordEncoder;
		this.defaults = defaults;
		this.clock = clock;
	}

	/** Invite: creates the account and its limits together (both or neither). */
	@Transactional
	public Mono<Member> invite(String email, String temporaryPassword, LimitsUpdate limits) {
		if (temporaryPassword == null || temporaryPassword.length() < MIN_PASSWORD_LENGTH) {
			return Mono.error(new InvalidMemberException(
					"password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
		}
		MemberLimits merged;
		try {
			merged = validate(applyUpdate(defaultLimits(null), limits));
		}
		catch (InvalidMemberException e) {
			return Mono.error(e);
		}
		String normalized = User.normalizeEmail(email);
		User newUser = User.newUser(normalized, passwordEncoder.encode(temporaryPassword), Role.MEMBER);

		return users.findByEmail(normalized)
				.flatMap(existing -> Mono.<User>error(new EmailTakenException()))
				.switchIfEmpty(Mono.defer(() -> users.save(newUser)))
				.onErrorMap(DuplicateKeyException.class, e -> new EmailTakenException())
				.flatMap(user -> template.insert(withUser(merged, user.id()))
						.map(saved -> new Member(user, saved)))
				.doOnNext(m -> log.info("Invited member {} ({})", m.user().email(), m.user().id()));
	}

	public Flux<Member> list() {
		return users.findAllByRoleOrderByCreatedAtAsc(Role.MEMBER)
				.concatMap(u -> limitsFor(u).map(l -> new Member(u, l)));
	}

	public Mono<Member> get(UUID id) {
		return findMember(id).flatMap(u -> limitsFor(u).map(l -> new Member(u, l)));
	}

	/** Partial update: only the fields that are present change. */
	@Transactional
	public Mono<Member> update(UUID id, UserStatus newStatus, LimitsUpdate changes) {
		return findMember(id).flatMap(user -> limitsFor(user).flatMap(current -> {
			MemberLimits merged;
			try {
				merged = validate(applyUpdate(current, changes));
			}
			catch (InvalidMemberException e) {
				return Mono.error(e);
			}
			Mono<User> savedUser = (newStatus == null || newStatus == user.status()) ? Mono.just(user)
					: users.save(new User(user.id(), user.email(), user.passwordHash(), user.role(), newStatus,
							user.createdAt(), now()));
			return savedUser.flatMap(u -> limitsRepository.save(merged).map(l -> new Member(u, l)));
		})).doOnNext(m -> log.info("Updated member {} (status={})", m.user().id(), m.user().status()));
	}

	/** Removes the member. Their keys and limits are deleted too; past request logs are kept. */
	@Transactional
	public Mono<Void> remove(UUID id) {
		return findMember(id).flatMap(u -> users.delete(u)
				.doOnSuccess(v -> log.info("Removed member {} ({})", u.email(), u.id())));
	}

	// ---------- helpers ----------

	private Mono<User> findMember(UUID id) {
		return users.findById(id)
				.filter(u -> u.role() == Role.MEMBER)
				.switchIfEmpty(Mono.error(new MemberNotFoundException()));
	}

	/** A member's limits, used on every /v1 request. */
	public Mono<MemberLimits> limitsForUser(UUID userId) {
		return limitsRepository.findById(userId)
				.switchIfEmpty(Mono.defer(() -> template.insert(defaultLimits(userId))
						// Two first requests at once may both try to insert; the loser just re-reads.
						.onErrorResume(DuplicateKeyException.class, e -> limitsRepository.findById(userId))));
	}

	/** Members created before limits existed get the defaults. */
	private Mono<MemberLimits> limitsFor(User u) {
		return limitsForUser(u.id());
	}

	private MemberLimits defaultLimits(UUID userId) {
		return new MemberLimits(userId, defaults.dailyBudgetUsd(), defaults.monthlyBudgetUsd(), defaults.rpmLimit(),
				List.copyOf(defaults.allowedModels()), now());
	}

	private MemberLimits applyUpdate(MemberLimits current, LimitsUpdate u) {
		if (u == null) {
			return current;
		}
		return new MemberLimits(current.userId(),
				u.dailyBudgetUsd() != null ? u.dailyBudgetUsd() : current.dailyBudgetUsd(),
				u.monthlyBudgetUsd() != null ? u.monthlyBudgetUsd() : current.monthlyBudgetUsd(),
				u.rpmLimit() != null ? u.rpmLimit() : current.rpmLimit(),
				u.allowedModels() != null ? u.allowedModels() : current.allowedModels(),
				now());
	}

	/** Checks the combined result, e.g. a new daily budget against the existing monthly one. */
	static MemberLimits validate(MemberLimits l) {
		if (l.dailyBudgetUsd().signum() < 0 || l.monthlyBudgetUsd().signum() < 0) {
			throw new InvalidMemberException("budgets must not be negative");
		}
		if (l.dailyBudgetUsd().compareTo(MAX_BUDGET) > 0 || l.monthlyBudgetUsd().compareTo(MAX_BUDGET) > 0) {
			throw new InvalidMemberException("budgets must not exceed " + MAX_BUDGET.toPlainString() + " USD");
		}
		if (l.dailyBudgetUsd().stripTrailingZeros().scale() > 2
				|| l.monthlyBudgetUsd().stripTrailingZeros().scale() > 2) {
			throw new InvalidMemberException("budgets can have at most 2 decimal places (cents)");
		}
		if (l.monthlyBudgetUsd().compareTo(l.dailyBudgetUsd()) < 0) {
			throw new InvalidMemberException("monthly_budget_usd must be at least daily_budget_usd");
		}
		if (l.rpmLimit() < 1 || l.rpmLimit() > 10000) {
			throw new InvalidMemberException("rpm_limit must be between 1 and 10000");
		}
		if (l.allowedModels().size() > MAX_ALLOWED_MODELS) {
			throw new InvalidMemberException("at most " + MAX_ALLOWED_MODELS + " allowed models");
		}
		LinkedHashSet<String> models = new LinkedHashSet<>();
		for (String m : l.allowedModels()) {
			String name = m == null ? "" : m.trim();
			if (!MODEL_NAME.matcher(name).matches()) {
				throw new InvalidMemberException("invalid model name: '" + name + "'");
			}
			models.add(name);
		}
		return new MemberLimits(l.userId(), l.dailyBudgetUsd(), l.monthlyBudgetUsd(), l.rpmLimit(),
				List.copyOf(models), l.updatedAt());
	}

	private static MemberLimits withUser(MemberLimits l, UUID userId) {
		return new MemberLimits(userId, l.dailyBudgetUsd(), l.monthlyBudgetUsd(), l.rpmLimit(), l.allowedModels(),
				l.updatedAt());
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	// ---------- types ----------

	public record Member(User user, MemberLimits limits) {
	}

	/** Any field may be null, meaning "leave unchanged" (or "use the default" when inviting). */
	public record LimitsUpdate(BigDecimal dailyBudgetUsd, BigDecimal monthlyBudgetUsd, Integer rpmLimit,
			List<String> allowedModels) {
	}

	public static class MemberNotFoundException extends RuntimeException {
		public MemberNotFoundException() {
			super("Member not found");
		}
	}

	public static class EmailTakenException extends RuntimeException {
		public EmailTakenException() {
			super("An account with this email already exists");
		}
	}

	public static class InvalidMemberException extends RuntimeException {
		public InvalidMemberException(String message) {
			super("Invalid request: " + message);
		}
	}
}
