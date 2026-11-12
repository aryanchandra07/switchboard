package com.switchboard.gateway.limits;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.switchboard.gateway.chat.RequestContext;
import com.switchboard.gateway.chat.UsagePolicy;
import com.switchboard.gateway.member.MemberLimits;
import com.switchboard.gateway.member.MemberService;

import reactor.core.publisher.Mono;

/**
 * Enforces each member's allowed models, requests-per-minute and daily/monthly budgets
 * (FR-06, FR-07), in that order: a refused model doesn't use up rate-limit quota.
 *
 * <p>A request is refused once spending has <em>reached</em> the budget; the request that crosses the
 * line is allowed to finish, because its cost is only known after the model replies.
 */
@Component
public class MemberUsagePolicy implements UsagePolicy {

	private final MemberService members;
	private final UsageLimiter limiter;

	public MemberUsagePolicy(MemberService members, UsageLimiter limiter) {
		this.members = members;
		this.limiter = limiter;
	}

	@Override
	public Mono<String> check(RequestContext ctx, String model, boolean auto) {
		if (!ctx.isLimited()) {
			return Mono.just(model);
		}
		return members.limitsForUser(ctx.userId())
				.flatMap(limits -> chooseModel(model, auto, limits.allowedModels())
						.flatMap(chosen -> limiter.checkRate(ctx.userId(), limits.rpmLimit())
								.then(limiter.currentSpend(ctx.userId()))
								.flatMap(spend -> checkBudget(spend, limits))
								.thenReturn(chosen)));
	}

	/**
	 * A named model must be on the member's list. For "auto", use the default model if it's allowed,
	 * otherwise the first model on the member's list. (Person B's router will make this smarter.)
	 */
	static Mono<String> chooseModel(String model, boolean auto, List<String> allowed) {
		if (allowed.contains(model)) {
			return Mono.just(model);
		}
		if (auto && !allowed.isEmpty()) {
			return Mono.just(allowed.get(0));
		}
		return Mono.error(new RequestBlockedException.ModelNotAllowed(auto ? "auto" : model, allowed));
	}

	/** Failover targets must be on the member's allowed list too. */
	@Override
	public Mono<List<String>> allowedAmong(RequestContext ctx, List<String> models) {
		if (!ctx.isLimited()) {
			return Mono.just(models);
		}
		return members.limitsForUser(ctx.userId())
				.map(limits -> models.stream().filter(limits.allowedModels()::contains).toList());
	}

	static Mono<Void> checkBudget(Spend spend, MemberLimits limits) {
		if (spend.daily().compareTo(limits.dailyBudgetUsd()) >= 0) {
			return Mono.error(new RequestBlockedException.BudgetExceeded("daily", spend, limits.dailyBudgetUsd(),
					limits.monthlyBudgetUsd()));
		}
		if (spend.monthly().compareTo(limits.monthlyBudgetUsd()) >= 0) {
			return Mono.error(new RequestBlockedException.BudgetExceeded("monthly", spend, limits.dailyBudgetUsd(),
					limits.monthlyBudgetUsd()));
		}
		return Mono.empty();
	}

	@Override
	public void recordSpend(RequestContext ctx, BigDecimal costUsd) {
		if (ctx.isLimited()) {
			limiter.addSpend(ctx.userId(), costUsd).subscribe();
		}
	}
}
