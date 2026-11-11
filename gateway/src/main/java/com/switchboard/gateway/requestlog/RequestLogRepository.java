package com.switchboard.gateway.requestlog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Mono;

public interface RequestLogRepository extends ReactiveCrudRepository<RequestLog, UUID> {

	/** Total cost of a member's requests since a point in time. The source of truth for spend. */
	@Query("SELECT COALESCE(SUM(cost_usd), 0) FROM requests WHERE user_id = :userId AND created_at >= :since")
	Mono<BigDecimal> sumCostSince(UUID userId, Instant since);
}
