package com.switchboard.gateway.api;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatCompletionResponse;
import com.switchboard.gateway.auth.MemberPrincipal;
import com.switchboard.gateway.chat.ChatCompletionService;
import com.switchboard.gateway.chat.RequestContext;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

/** OpenAI-compatible chat endpoint (FR-08). Needs an sb- key or a web login token. */
@RestController
public class ChatCompletionController {

	private final ChatCompletionService service;

	public ChatCompletionController(ChatCompletionService service) {
		this.service = service;
	}

	@PostMapping(path = "/v1/chat/completions",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ChatCompletionResponse> chatCompletions(@AuthenticationPrincipal MemberPrincipal caller,
			@Valid @RequestBody ChatCompletionRequest request) {
		return service.complete(request, new RequestContext(caller.userId(), caller.apiKeyId(), caller.role()));
	}
}
