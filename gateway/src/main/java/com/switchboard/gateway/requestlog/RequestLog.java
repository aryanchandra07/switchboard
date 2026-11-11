package com.switchboard.gateway.requestlog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A row in "requests": what happened for one chat call. There is deliberately no field for the
 * prompt or the reply, so they can never end up in the database by accident.
 */
@Table("requests")
public record RequestLog(
		@Id UUID id,
		@Column("user_id") UUID userId,
		@Column("api_key_id") UUID apiKeyId,
		String provider,
		String model,
		@Column("router_score") Double routerScore,
		@Column("tokens_in") int tokensIn,
		@Column("tokens_out") int tokensOut,
		@Column("cost_usd") BigDecimal costUsd,
		@Column("latency_ms") int latencyMs,
		@Column("cache_hit") boolean cacheHit,
		Status status,
		@Column("error_code") String errorCode,
		@Column("blocked_reason") String blockedReason,
		@Column("created_at") Instant createdAt) {

	public enum Status {
		SUCCESS, ERROR, BLOCKED
	}
}
