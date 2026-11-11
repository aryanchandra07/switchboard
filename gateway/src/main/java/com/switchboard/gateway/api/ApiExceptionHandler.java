package com.switchboard.gateway.api;

import java.util.stream.Collectors;

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
import com.switchboard.gateway.auth.AuthException;
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
