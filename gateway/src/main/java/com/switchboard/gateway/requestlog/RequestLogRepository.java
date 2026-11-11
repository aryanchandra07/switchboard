package com.switchboard.gateway.requestlog;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface RequestLogRepository extends ReactiveCrudRepository<RequestLog, UUID> {
}
