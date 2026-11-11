package com.switchboard.gateway.api;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.switchboard.gateway.auth.MemberPrincipal;
import com.switchboard.gateway.member.MemberService;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ollama.OllamaProperties;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GET /v1/models (OpenAI-compatible): the models the caller may use. Members see their allowed list;
 * the admin sees every model installed in Ollama.
 */
@RestController
public class ModelsController {

	private static final Logger log = LoggerFactory.getLogger(ModelsController.class);

	private final MemberService members;
	private final LlmProvider provider;
	private final OllamaProperties ollama;

	public ModelsController(MemberService members, LlmProvider provider, OllamaProperties ollama) {
		this.members = members;
		this.provider = provider;
		this.ollama = ollama;
	}

	@GetMapping("/v1/models")
	public Mono<ModelList> list(@AuthenticationPrincipal MemberPrincipal caller) {
		Mono<List<String>> names = "MEMBER".equals(caller.role())
				? members.limitsForUser(caller.userId()).map(l -> l.allowedModels())
				: installedModels();
		return names.map(list -> new ModelList("list", list.stream().map(ModelsController::entry).toList()));
	}

	private Mono<List<String>> installedModels() {
		return Flux.defer(() -> {
			Flux<String> f = provider.listModels();
			return f == null ? Flux.<String>empty() : f;
		})
				.collectList()
				.onErrorResume(e -> {
					log.warn("Could not list Ollama models: {}", e.toString());
					return Mono.just(List.of());
				})
				.map(list -> list.isEmpty() ? List.of(ollama.defaultModel()) : list);
	}

	static ModelEntry entry(String name) {
		String owner = name.toLowerCase(Locale.ROOT).startsWith("claude") ? "anthropic" : "ollama";
		return new ModelEntry(name, "model", 0, owner);
	}

	public record ModelList(String object, List<ModelEntry> data) {
	}

	public record ModelEntry(String id, String object, long created, @JsonProperty("owned_by") String ownedBy) {
	}
}
