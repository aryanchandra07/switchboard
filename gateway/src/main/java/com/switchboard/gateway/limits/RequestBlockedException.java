package com.switchboard.gateway.limits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** A request refused by the gateway's own rules (FR-07). Logged as BLOCKED with {@link #reason()}. */
public abstract sealed class RequestBlockedException extends RuntimeException
		permits RequestBlockedException.RateLimited, RequestBlockedException.BudgetExceeded,
		RequestBlockedException.ModelNotAllowed {

	protected RequestBlockedException(String message) {
		super(message);
	}

	/** Short code stored in requests.blocked_reason and returned as error.code. */
	public abstract String reason();

	/** Too many requests in the current minute. */
	public static final class RateLimited extends RequestBlockedException {
		private final int limit;
		private final long retryAfterSeconds;

		public RateLimited(int limit, long retryAfterSeconds) {
			super("Rate limit of " + limit + " requests per minute exceeded. Try again in " + retryAfterSeconds
					+ " seconds.");
			this.limit = limit;
			this.retryAfterSeconds = retryAfterSeconds;
		}

		public int limit() {
			return limit;
		}

		public long retryAfterSeconds() {
			return retryAfterSeconds;
		}

		@Override
		public String reason() {
			return "rate_limit_exceeded";
		}
	}

	/** The member asked for a model the admin hasn't allowed them. */
	public static final class ModelNotAllowed extends RequestBlockedException {
		private final java.util.List<String> allowed;

		public ModelNotAllowed(String model, java.util.List<String> allowed) {
			super(allowed.isEmpty() ? "You are not allowed to use any models. Ask the admin to allow some."
					: "You are not allowed to use model '" + model + "'. Allowed: " + String.join(", ", allowed));
			this.allowed = java.util.List.copyOf(allowed);
		}

		public java.util.List<String> allowed() {
			return allowed;
		}

		@Override
		public String reason() {
			return "model_not_allowed";
		}
	}

	/** Daily or monthly spending has reached the member's budget. */
	public static final class BudgetExceeded extends RequestBlockedException {
		private final String period; // "daily" or "monthly"
		private final Spend spend;
		private final BigDecimal dailyBudget;
		private final BigDecimal monthlyBudget;

		public BudgetExceeded(String period, Spend spend, BigDecimal dailyBudget, BigDecimal monthlyBudget) {
			super("Your " + period + " budget of $" + money(period.equals("daily") ? dailyBudget : monthlyBudget)
					+ " has been used up." + (period.equals("daily") ? " It resets at 00:00 UTC."
							: " It resets on the 1st of the month (UTC)."));
			this.period = period;
			this.spend = spend;
			this.dailyBudget = dailyBudget;
			this.monthlyBudget = monthlyBudget;
		}

		@Override
		public String reason() {
			return period + "_budget_exceeded";
		}

		/** Budget numbers included in the error response, so the member can see where they stand. */
		public Map<String, Object> details() {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("daily_budget_usd", money(dailyBudget));
			d.put("daily_spent_usd", money(spend.daily()));
			d.put("daily_remaining_usd", money(remaining(dailyBudget, spend.daily())));
			d.put("monthly_budget_usd", money(monthlyBudget));
			d.put("monthly_spent_usd", money(spend.monthly()));
			d.put("monthly_remaining_usd", money(remaining(monthlyBudget, spend.monthly())));
			return d;
		}

		private static BigDecimal remaining(BigDecimal budget, BigDecimal spent) {
			return budget.subtract(spent).max(BigDecimal.ZERO);
		}
	}

	static BigDecimal money(BigDecimal v) {
		return v.setScale(2, RoundingMode.HALF_UP);
	}
}
