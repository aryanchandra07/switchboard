package com.switchboard.gateway.api;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import com.switchboard.gateway.api.dto.ErrorResponse;
import com.switchboard.gateway.apikey.ApiKeyService;
import com.switchboard.gateway.auth.AuthException;
import com.switchboard.gateway.limits.RequestBlockedException;
import com.switchboard.gateway.member.MemberService;
import com.switchboard.gateway.provider.InvalidProviderRequestException;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.providerkey.ProviderKeyService;

/** Turns exceptions into consistent OpenAI-style JSON errors. */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** @Valid failed, e.g. empty "messages" or blank "role". */
	@ExceptionHandler(WebExchangeBindException.class)
	ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException e) {
		String details = e.getFieldErrors().stream()
				.map(f -> f.getField() + " " + f.getDefaultMessage())
				.sorted()
				.collect(Collectors.joining("; "));
		return error(HttpStatus.BAD_REQUEST, "Invalid request: " + details, "invalid_request_error", "invalid_body");
	}

	/** Body missing or not valid JSON. */
	@ExceptionHandler(ServerWebInputException.class)
	ResponseEntity<ErrorResponse> handleBadInput(ServerWebInputException e) {
		return error(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON",
				"invalid_request_error", "invalid_json");
	}

	/** Wrong content type, unknown path, etc. Keep Spring's status code. */
	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException e) {
		HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
		String message = e.getReason() != null ? e.getReason()
				: (status != null ? status.getReasonPhrase() : "Request failed");
		return ResponseEntity.status(e.getStatusCode())
				.body(ErrorResponse.of(message, "invalid_request_error", null));
	}

	/** Wrong email/password → 401. */
	@ExceptionHandler(AuthException.InvalidCredentials.class)
	ResponseEntity<ErrorResponse> handleInvalidCredentials(AuthException e) {
		return error(HttpStatus.UNAUTHORIZED, e.getMessage(), "authentication_error", "invalid_credentials");
	}

	/** Account disabled by the admin → 403 (FR-03). */
	@ExceptionHandler(AuthException.AccountDisabled.class)
	ResponseEntity<ErrorResponse> handleDisabled(AuthException e) {
		return error(HttpStatus.FORBIDDEN, e.getMessage(), "permission_error", "account_disabled");
	}

	/** PUT /admin/providers/{provider} with a provider we don't support → 400. */
	@ExceptionHandler(ProviderKeyService.UnknownProviderException.class)
	ResponseEntity<ErrorResponse> handleUnknownProvider(ProviderKeyService.UnknownProviderException e) {
		return error(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_request_error", "unknown_provider");
	}

	/** Member already has the maximum number of active keys → 409. */
	@ExceptionHandler(ApiKeyService.TooManyKeysException.class)
	ResponseEntity<ErrorResponse> handleTooManyKeys(ApiKeyService.TooManyKeysException e) {
		return error(HttpStatus.CONFLICT, e.getMessage(), "invalid_request_error", "too_many_keys");
	}

	@ExceptionHandler(MemberService.MemberNotFoundException.class)
	ResponseEntity<ErrorResponse> handleMemberNotFound(MemberService.MemberNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, e.getMessage(), "invalid_request_error", "member_not_found");
	}

	@ExceptionHandler(MemberService.EmailTakenException.class)
	ResponseEntity<ErrorResponse> handleEmailTaken(MemberService.EmailTakenException e) {
		return error(HttpStatus.CONFLICT, e.getMessage(), "invalid_request_error", "email_taken");
	}

	@ExceptionHandler(MemberService.InvalidMemberException.class)
	ResponseEntity<ErrorResponse> handleInvalidMember(MemberService.InvalidMemberException e) {
		return error(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_request_error", "invalid_body");
	}

	/** Over the per-minute limit → 429 with Retry-After (FR-07). */
	@ExceptionHandler(RequestBlockedException.RateLimited.class)
	ResponseEntity<ErrorResponse> handleRateLimited(RequestBlockedException.RateLimited e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
				.body(ErrorResponse.of(e.getMessage(), "rate_limit_error", e.reason(),
						Map.of("rpm_limit", e.limit(), "retry_after_seconds", e.retryAfterSeconds())));
	}

	/** Model not on the member's allowed list → 403 with the list (FR-06). */
	@ExceptionHandler(RequestBlockedException.ModelNotAllowed.class)
	ResponseEntity<ErrorResponse> handleModelNotAllowed(RequestBlockedException.ModelNotAllowed e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ErrorResponse.of(e.getMessage(), "permission_error", e.reason(),
						Map.of("allowed_models", e.allowed())));
	}

	/** Budget used up → 429 with the remaining budget (FR-07). */
	@ExceptionHandler(RequestBlockedException.BudgetExceeded.class)
	ResponseEntity<ErrorResponse> handleBudget(RequestBlockedException.BudgetExceeded e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.body(ErrorResponse.of(e.getMessage(), "insufficient_quota", e.reason(), e.details()));
	}

	/** Something the client asked for that the provider can't accept (e.g. max_tokens too large) → 400. */
	@ExceptionHandler(InvalidProviderRequestException.class)
	ResponseEntity<ErrorResponse> handleInvalidProviderRequest(InvalidProviderRequestException e) {
		return error(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_request_error", "invalid_model_request");
	}

	/** The model backend failed (down, timeout, model not found). */
	@ExceptionHandler(ProviderException.class)
	ResponseEntity<ErrorResponse> handleProvider(ProviderException e) {
		log.warn("Provider {} failed: {}", e.provider(), e.getMessage());
		return error(HttpStatus.BAD_GATEWAY, e.getMessage(), "provider_error", e.provider() + "_unavailable");
	}

	/** Anything unexpected: log the details, but don't leak them to the client. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("Unexpected error", e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "server_error", null);
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message, String type, String code) {
		return ResponseEntity.status(status).body(ErrorResponse.of(message, type, code));
	}
}
