package com.switchboard.gateway.api.dto;

/**
 * OpenAI-style error body: {"error": {"message": "...", "type": "...", "code": "..."}}.
 * OpenAI client libraries know how to read this shape.
 */
public record ErrorResponse(Error error) {

	public record Error(String message, String type, String code) {
	}

	public static ErrorResponse of(String message, String type, String code) {
		return new ErrorResponse(new Error(message, type, code));
	}
}
