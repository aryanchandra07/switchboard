package com.switchboard.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.switchboard.gateway.chat.RequestContext;
import com.switchboard.gateway.member.MemberLimits;
import com.switchboard.gateway.member.MemberService;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MemberUsagePolicyTest {

	private final MemberService members = mock(MemberService.class);
	private final UsageLimiter limiter = mock(UsageLimiter.class);
	private final MemberUsagePolicy policy = new MemberUsagePolicy(members, limiter);
	private final UUID userId = UUID.randomUUID();

	private static MemberLimits limits(String daily, String monthly) {
		return new MemberLimits(null, new BigDecimal(daily), new BigDecimal(monthly), 10, List.of("llama3.2"),
				Instant.now());
	}

	private static Spend spend(String daily, String monthly) {
		return new Spend(new BigDecimal(daily), new BigDecimal(monthly));
	}

	// ---------- budget rule ----------

	@Test
	void underBothBudgets_isAllowed() {
		StepVerifier.create(MemberUsagePolicy.checkBudget(spend("0.99", "5"), limits("1", "20"))).verifyComplete();
	}

	@Test
	void dailyReached_isBlockedWithDetails() {
		StepVerifier.create(MemberUsagePolicy.checkBudget(spend("1.00", "5.00"), limits("1", "20")))
				.expectErrorSatisfies(e -> {
					var b = (RequestBlockedException.BudgetExceeded) e;
					assertThat(b.reason()).isEqualTo("daily_budget_exceeded");
					assertThat(b.getMessage()).contains("daily budget of $1.00").contains("00:00 UTC");
					Map<String, Object> d = b.details();
					assertThat(d.get("daily_remaining_usd")).isEqualTo(new BigDecimal("0.00"));
					assertThat(d.get("monthly_remaining_usd")).isEqualTo(new BigDecimal("15.00"));
					assertThat(d.get("daily_spent_usd")).isEqualTo(new BigDecimal("1.00"));
				})
				.verify();
	}

	@Test
	void monthlyReached_isBlocked_evenIfDailyIsFine() {
		StepVerifier.create(MemberUsagePolicy.checkBudget(spend("0.10", "20.00"), limits("5", "20")))
				.expectErrorSatisfies(e -> assertThat(((RequestBlockedException) e).reason())
						.isEqualTo("monthly_budget_exceeded"))
				.verify();
	}

	@Test
	void overspend_showsZeroRemaining_notNegative() {
		StepVerifier.create(MemberUsagePolicy.checkBudget(spend("1.37", "1.37"), limits("1", "20")))
				.expectErrorSatisfies(e -> assertThat(((RequestBlockedException.BudgetExceeded) e).details()
						.get("daily_remaining_usd")).isEqualTo(new BigDecimal("0.00")))
				.verify();
	}

	@Test
	void zeroBudget_blocksEverything() {
		StepVerifier.create(MemberUsagePolicy.checkBudget(spend("0", "0"), limits("0", "0")))
				.expectError(RequestBlockedException.BudgetExceeded.class).verify();
	}

	// ---------- who is limited ----------

	@Test
	void member_isCheckedForRateThenBudget() {
		when(members.limitsForUser(userId)).thenReturn(Mono.just(limits("1", "20")));
		when(limiter.checkRate(userId, 10)).thenReturn(Mono.empty());
		when(limiter.currentSpend(userId)).thenReturn(Mono.just(spend("0", "0")));

		StepVerifier.create(policy.check(new RequestContext(userId, null, "MEMBER"), "llama3.2", false))
				.expectNext("llama3.2").verifyComplete();
		verify(limiter).checkRate(userId, 10);
		verify(limiter).currentSpend(userId);
	}

	@Test
	void rateLimited_doesNotEvenLookAtSpend() {
		when(members.limitsForUser(userId)).thenReturn(Mono.just(limits("1", "20")));
		when(limiter.checkRate(userId, 10)).thenReturn(Mono.error(new RequestBlockedException.RateLimited(10, 30)));
		when(limiter.currentSpend(any())).thenReturn(Mono.just(spend("0", "0")));

		StepVerifier.create(policy.check(new RequestContext(userId, null, "MEMBER"), "llama3.2", false))
				.expectError(RequestBlockedException.RateLimited.class).verify();
	}

	@Test
	void admin_andAnonymous_areNotLimited() {
		StepVerifier.create(policy.check(new RequestContext(userId, null, "ADMIN"), "claude-opus", false))
				.expectNext("claude-opus").verifyComplete();
		StepVerifier.create(policy.check(RequestContext.anonymous(), "x", false)).expectNext("x").verifyComplete();
		verifyNoInteractions(members, limiter);
	}

	@Test
	void recordSpend_onlyForMembers() {
		when(limiter.addSpend(any(), any())).thenReturn(Mono.empty());
		policy.recordSpend(new RequestContext(userId, null, "MEMBER"), new BigDecimal("0.10"));
		verify(limiter).addSpend(userId, new BigDecimal("0.10"));

		policy.recordSpend(new RequestContext(UUID.randomUUID(), null, "ADMIN"), new BigDecimal("0.10"));
		verify(limiter, never()).addSpend(org.mockito.ArgumentMatchers.argThat(id -> !id.equals(userId)), any());
	}

	// ---------- allowed models ----------

	@Test
	void chooseModel_namedAndAllowed() {
		StepVerifier.create(MemberUsagePolicy.chooseModel("qwen2.5:7b", false, List.of("llama3.2", "qwen2.5:7b")))
				.expectNext("qwen2.5:7b").verifyComplete();
	}

	@Test
	void chooseModel_namedButNotAllowed_isRefusedWithList() {
		StepVerifier.create(MemberUsagePolicy.chooseModel("claude-opus", false, List.of("llama3.2")))
				.expectErrorSatisfies(e -> {
					var m = (RequestBlockedException.ModelNotAllowed) e;
					assertThat(m.reason()).isEqualTo("model_not_allowed");
					assertThat(m.allowed()).containsExactly("llama3.2");
					assertThat(m.getMessage()).contains("claude-opus").contains("llama3.2");
				})
				.verify();
	}

	@Test
	void chooseModel_autoUsesDefaultIfAllowed_elseFirstAllowed() {
		StepVerifier.create(MemberUsagePolicy.chooseModel("llama3.2", true, List.of("qwen2.5:7b", "llama3.2")))
				.expectNext("llama3.2").verifyComplete();
		StepVerifier.create(MemberUsagePolicy.chooseModel("llama3.2", true, List.of("qwen2.5:7b", "claude-haiku")))
				.expectNext("qwen2.5:7b").verifyComplete();
	}

	@Test
	void chooseModel_emptyList_refusesEverything() {
		StepVerifier.create(MemberUsagePolicy.chooseModel("llama3.2", true, List.of()))
				.expectErrorSatisfies(e -> assertThat(e.getMessage()).contains("not allowed to use any models"))
				.verify();
		StepVerifier.create(MemberUsagePolicy.chooseModel("llama3.2", false, List.of()))
				.expectError(RequestBlockedException.ModelNotAllowed.class).verify();
	}

	@Test
	void modelCaseMustMatchExactly() {
		StepVerifier.create(MemberUsagePolicy.chooseModel("LLAMA3.2", false, List.of("llama3.2")))
				.expectError(RequestBlockedException.ModelNotAllowed.class).verify();
	}

	@Test
	void notAllowedModel_doesNotUseRateLimitQuota() {
		when(members.limitsForUser(userId)).thenReturn(Mono.just(limits("1", "20")));
		StepVerifier.create(policy.check(new RequestContext(userId, null, "MEMBER"), "claude-opus", false))
				.expectError(RequestBlockedException.ModelNotAllowed.class).verify();
		verifyNoInteractions(limiter);
	}
}
