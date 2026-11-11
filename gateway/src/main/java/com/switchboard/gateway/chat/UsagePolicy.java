package com.switchboard.gateway.chat;

import java.math.BigDecimal;

import reactor.core.publisher.Mono;

/** Checks a member's limits before a model call and records what the call cost afterwards. */
public interface UsagePolicy {

	/**
	 * Decides whether the request may go ahead and which model it uses.
	 *
	 * @param model the requested model, or the default model when {@code auto} is true
	 * @param auto  true when the client asked for "auto" (or sent no model)
	 * @return the model to call, or an error (RequestBlockedException) if the request is refused
	 */
	Mono<String> check(RequestContext context, String model, boolean auto);

	/** Records the cost of a successful call. Must never throw or delay the response. */
	void recordSpend(RequestContext context, BigDecimal costUsd);

	/** No limits at all. Used in unit tests. */
	UsagePolicy NONE = new UsagePolicy() {
		@Override
		public Mono<String> check(RequestContext context, String model, boolean auto) {
			return Mono.just(model);
		}

		@Override
		public void recordSpend(RequestContext context, BigDecimal costUsd) {
		}
	};
}
