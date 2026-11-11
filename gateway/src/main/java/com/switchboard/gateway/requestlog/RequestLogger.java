package com.switchboard.gateway.requestlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Saves request logs without slowing down or breaking the member's response:
 * {@link #record} starts the database write in the background and returns immediately,
 * and a failed write is only reported in the application log.
 */
@Component
public class RequestLogger {

	private static final Logger log = LoggerFactory.getLogger(RequestLogger.class);

	private final RequestLogRepository repository;

	public RequestLogger(RequestLogRepository repository) {
		this.repository = repository;
	}

	/** Fire-and-forget: never throws, never delays the caller. */
	public void record(RequestLog entry) {
		save(entry).subscribe();
	}

	/** The actual write; errors are logged and swallowed. Visible for tests. */
	Mono<Void> save(RequestLog entry) {
		return Mono.defer(() -> repository.save(entry))
				.then()
				.onErrorResume(e -> {
					log.warn("Could not save request log (status={}, model={}): {}", entry.status(), entry.model(),
							e.toString());
					return Mono.empty();
				});
	}
}
