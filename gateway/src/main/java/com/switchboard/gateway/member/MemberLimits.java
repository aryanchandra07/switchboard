package com.switchboard.gateway.member;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A row in "member_limits": one per member. */
@Table("member_limits")
public record MemberLimits(
		@Id @Column("user_id") UUID userId,
		@Column("daily_budget_usd") BigDecimal dailyBudgetUsd,
		@Column("monthly_budget_usd") BigDecimal monthlyBudgetUsd,
		@Column("rpm_limit") int rpmLimit,
		@Column("allowed_models") List<String> allowedModels,
		@Column("updated_at") Instant updatedAt) {
}
