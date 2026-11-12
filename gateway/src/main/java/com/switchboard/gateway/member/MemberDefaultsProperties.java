package com.switchboard.gateway.member;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits a new member gets when the admin doesn't specify them (prefix "switchboard.members.defaults"). */
@ConfigurationProperties(prefix = "switchboard.members.defaults")
public record MemberDefaultsProperties(
		BigDecimal dailyBudgetUsd,
		BigDecimal monthlyBudgetUsd,
		int rpmLimit,
		List<String> allowedModels) {
}
