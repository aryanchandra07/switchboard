package com.switchboard.gateway.limits;

import java.math.BigDecimal;

/** How much a member has spent today and this month (UTC), in USD. */
public record Spend(BigDecimal daily, BigDecimal monthly) {
}
