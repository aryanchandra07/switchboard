package com.switchboard.gateway.member;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reads member limits. New rows are inserted with R2dbcEntityTemplate.insert(), because the id
 * (user_id) is always set, and Spring Data would otherwise treat save() as an UPDATE.
 */
public interface MemberLimitsRepository extends ReactiveCrudRepository<MemberLimits, UUID> {
}
