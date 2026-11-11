package com.switchboard.gateway.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatCompletionResponse;
import com.switchboard.gateway.chat.ChatCompletionService;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

/** OpenAI-compatible chat endpoint (FR-08). */
@RestController
public class ChatCompletionController {

	private final ChatCompletionService service;

	public ChatCompletionController(ChatCompletionService service) {
		this.service = service;
	}

	@PostMapping(path = "/v1/chat/completions",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ChatCompletionResponse> chatCompletions(@Valid @RequestBody ChatCompletionRequest request) {
		return service.complete(request);
	}
}
