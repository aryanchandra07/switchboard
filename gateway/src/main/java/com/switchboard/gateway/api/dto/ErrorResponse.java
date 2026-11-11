package com.switchboard.gateway.api.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI-style error body: {"error": {"message": "...", "type": "...", "code": "..."}}.
 * OpenAI client libraries know how to read this shape. Some errors add a "details" object
 * (e.g. remaining budget).
 */
public record ErrorResponse(Error error) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Error(String message, String type, String code, Map<String, Object> details) {
	}

	public static ErrorResponse of(String message, String type, String code) {
		return new ErrorResponse(new Error(message, type, code, null));
	}

	public static ErrorResponse of(String message, String type, String code, Map<String, Object> details) {
		return new ErrorResponse(new Error(message, type, code, details));
	}
}
