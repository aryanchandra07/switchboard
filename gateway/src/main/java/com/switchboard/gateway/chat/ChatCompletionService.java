package com.switchboard.gateway.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.switchboard.gateway.api.dto.ChatCompletionRequest;
import com.switchboard.gateway.api.dto.ChatCompletionResponse;
import com.switchboard.gateway.api.dto.ChatMessage;
import com.switchboard.gateway.cache.CacheKeyGenerator;
import com.switchboard.gateway.cache.ExactMatchCache;
import com.switchboard.gateway.limits.RequestBlockedException;
import com.switchboard.gateway.provider.LlmProvider;
import com.switchboard.gateway.provider.ProviderException;
import com.switchboard.gateway.provider.ProviderRegistry;
import com.switchboard.gateway.provider.ProviderRequest;
import com.switchboard.gateway.provider.ProviderResponse;
import com.switchboard.gateway.provider.ollama.OllamaProperties;
import com.switchboard.gateway.requestlog.RequestLog;
import com.switchboard.gateway.requestlog.RequestLogger;
import com.switchboard.gateway.resilience.FailoverProperties;
import com.switchboard.gateway.resilience.ResilientCaller;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

@Service
public class ChatCompletionService {

    private static final Logger log
            = LoggerFactory.getLogger(ChatCompletionService.class);

    static final String AUTO = "auto";

    private final ProviderRegistry providers;
    private final OllamaProperties ollamaProperties;
    private final RequestLogger requestLogger;
    private final UsagePolicy usagePolicy;
    private final ResilientCaller caller;
    private final List<String> fallbackModels;
    private final Clock clock;

    private ExactMatchCache exactMatchCache;
    private CacheKeyGenerator cacheKeyGenerator;
    private ObjectMapper cacheObjectMapper;

    @Autowired
    public ChatCompletionService(
            ProviderRegistry providers,
            OllamaProperties ollamaProperties,
            RequestLogger requestLogger,
            UsagePolicy usagePolicy,
            ResilientCaller caller,
            FailoverProperties failover) {

        this(
                providers,
                ollamaProperties,
                requestLogger,
                usagePolicy,
                caller,
                failover.fallbackModels(),
                Clock.systemUTC()
        );
    }

    @Autowired
    public void configureCache(
            ExactMatchCache exactMatchCache,
            CacheKeyGenerator cacheKeyGenerator,
            ObjectMapper objectMapper) {

        this.exactMatchCache = exactMatchCache;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.cacheObjectMapper = objectMapper;
    }

    ChatCompletionService(
            LlmProvider provider,
            OllamaProperties ollamaProperties,
            RequestLogger requestLogger) {

        this(
                provider,
                ollamaProperties,
                requestLogger,
                UsagePolicy.NONE,
                Clock.systemUTC()
        );
    }

    ChatCompletionService(
            LlmProvider provider,
            OllamaProperties ollamaProperties,
            RequestLogger requestLogger,
            Clock clock) {

        this(
                provider,
                ollamaProperties,
                requestLogger,
                UsagePolicy.NONE,
                clock
        );
    }

    ChatCompletionService(
            LlmProvider provider,
            OllamaProperties ollamaProperties,
            RequestLogger requestLogger,
            UsagePolicy usagePolicy,
            Clock clock) {

        this(
                ProviderRegistry.single(provider),
                ollamaProperties,
                requestLogger,
                usagePolicy,
                new ResilientCaller(noRetries()),
                List.of(),
                clock
        );
    }

    ChatCompletionService(
            ProviderRegistry providers,
            OllamaProperties ollamaProperties,
            RequestLogger requestLogger,
            UsagePolicy usagePolicy,
            ResilientCaller caller,
            List<String> fallbackModels,
            Clock clock) {

        this.providers = providers;
        this.ollamaProperties = ollamaProperties;
        this.requestLogger = requestLogger;
        this.usagePolicy = usagePolicy;
        this.caller = caller;
        this.fallbackModels = List.copyOf(fallbackModels);
        this.clock = clock;
    }

    static FailoverProperties noRetries() {
        return new FailoverProperties(
                1,
                Duration.ZERO,
                List.of(),
                1000,
                100,
                Duration.ofSeconds(1)
        );
    }

    private Mono<ChatCompletionResponse> findCachedResponse(
            String key,
            long start) {

        return exactMatchCache.get(key)
                .flatMap(json -> {
                    try {
                        ChatCompletionResponse cached
                                = cacheObjectMapper.readValue(
                                        json,
                                        ChatCompletionResponse.class
                                );

                        var old = cached.switchboard();

                        var metadata
                                = new ChatCompletionResponse.Switchboard(
                                        old.provider(),
                                        old.modelUsed(),
                                        old.routerScore(),
                                        BigDecimal.ZERO,
                                        Math.max(0, clock.millis() - start),
                                        true,
                                        old.fallbackFrom()
                                );

                        return Mono.just(
                                new ChatCompletionResponse(
                                        "chatcmpl-" + UUID.randomUUID(),
                                        cached.object(),
                                        clock.millis() / 1000,
                                        cached.model(),
                                        cached.choices(),
                                        cached.usage(),
                                        metadata
                                )
                        );

                    } catch (Exception e) {
                        log.warn("Invalid cached response", e);
                        return Mono.empty();
                    }
                })
                .onErrorResume(e -> {
                    log.warn("Redis cache lookup failed", e);
                    return Mono.empty();
                });
    }

    public Mono<ChatCompletionResponse> complete(
            ChatCompletionRequest request) {

        return complete(request, RequestContext.anonymous());
    }

    public Mono<ChatCompletionResponse> complete(
            ChatCompletionRequest request,
            RequestContext context) {

        boolean auto = isAuto(request.model());
        String requested = resolveModel(request.model());

        Mono<String> chosenModel
                = Mono.defer(()
                        -> usagePolicy.check(context, requested, auto)
                ).doOnError(
                        RequestBlockedException.class,
                        e -> requestLogger.record(
                                blockedLog(
                                        e,
                                        auto ? "auto" : requested,
                                        context
                                )
                        )
                );

        return chosenModel.flatMap(first
                -> Mono.defer(() -> {

                    long start = clock.millis();

                    return candidates(first, context)
                            .flatMap(models
                                    -> attempt(
                                    models,
                                    0,
                                    request,
                                    null
                            )
                            )
                            .map(result
                                    -> toResponse(
                                    result,
                                    first,
                                    clock.millis() - start
                            )
                            )
                            .doOnNext(response -> {
                                requestLogger.record(
                                        successLog(response, context)
                                );

                                usagePolicy.recordSpend(
                                        context,
                                        response.switchboard().costUsd()
                                );
                            })
                            .doOnError(
                                    e -> !(e instanceof RequestBlockedException),
                                    e -> requestLogger.record(
                                            errorLog(
                                                    e,
                                                    first,
                                                    clock.millis() - start,
                                                    context
                                            )
                                    )
                            );
                })
        );
    }

    private Mono<List<String>> candidates(
            String first,
            RequestContext context) {

        List<String> others = fallbackModels.stream()
                .filter(m -> !m.equals(first))
                .toList();

        if (others.isEmpty()) {
            return Mono.just(List.of(first));
        }

        return usagePolicy.allowedAmong(context, others)
                .map(allowed -> {

                    LinkedHashSet<String> all
                            = new LinkedHashSet<>();

                    all.add(first);
                    all.addAll(allowed);

                    return new ArrayList<>(all);
                });
    }

    private Mono<Attempt> attempt(
            List<String> models,
            int i,
            ChatCompletionRequest request,
            Throwable previous) {

        String model = models.get(i);

        LlmProvider provider
                = providers.forModel(model);

        return caller.call(
                provider,
                new ProviderRequest(
                        model,
                        request.messages(),
                        request.maxTokens()
                )
        )
                .map(r
                        -> new Attempt(
                        provider,
                        model,
                        r
                )
                )
                .onErrorResume(e -> {

                    boolean canTryNext
                            = i + 1 < models.size()
                            && (!(e instanceof ProviderException pe)
                            || pe.canFailOver());

                    if (!canTryNext) {
                        return Mono.error(e);
                    }

                    log.warn(
                            "Model {} failed ({}); failing over to {}",
                            model,
                            e.getMessage(),
                            models.get(i + 1)
                    );

                    return attempt(
                            models,
                            i + 1,
                            request,
                            e
                    );
                });
    }

    private record Attempt(
            LlmProvider provider,
            String model,
            ProviderResponse response) {
    }

    static boolean isAuto(String requested) {
        return requested == null
                || requested.isBlank()
                || AUTO.equalsIgnoreCase(requested.trim());
    }

    String resolveModel(String requested) {
        return isAuto(requested)
                ? ollamaProperties.defaultModel()
                : requested.trim();
    }

    private ChatCompletionResponse toResponse(
            Attempt a,
            String firstModel,
            long latencyMs) {

        ProviderResponse r = a.response();

        String modelUsed
                = r.model() == null
                ? a.model()
                : r.model();

        var choice
                = new ChatCompletionResponse.Choice(
                        0,
                        new ChatMessage(
                                "assistant",
                                r.content()
                        ),
                        r.finishReason()
                );

        var usage
                = new ChatCompletionResponse.Usage(
                        r.promptTokens(),
                        r.completionTokens(),
                        r.promptTokens()
                        + r.completionTokens()
                );

        BigDecimal cost
                = r.costUsd() == null
                ? BigDecimal.ZERO
                : r.costUsd();

        String fallbackFrom
                = a.model().equals(firstModel)
                ? null
                : firstModel;

        var meta
                = new ChatCompletionResponse.Switchboard(
                        a.provider().name(),
                        modelUsed,
                        null,
                        cost,
                        latencyMs,
                        false,
                        fallbackFrom
                );

        return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(),
                "chat.completion",
                clock.millis() / 1000,
                modelUsed,
                List.of(choice),
                usage,
                meta
        );
    }

    private RequestLog successLog(
            ChatCompletionResponse r,
            RequestContext ctx) {

        var meta = r.switchboard();

        return new RequestLog(
                null,
                ctx.userId(),
                ctx.apiKeyId(),
                meta.provider(),
                meta.modelUsed(),
                meta.routerScore(),
                r.usage().promptTokens(),
                r.usage().completionTokens(),
                meta.costUsd(),
                (int) meta.latencyMs(),
                meta.cacheHit(),
                RequestLog.Status.SUCCESS,
                null,
                null,
                now()
        );
    }

    private RequestLog errorLog(
            Throwable e,
            String model,
            long latencyMs,
            RequestContext ctx) {

        String provider
                = e instanceof ProviderException pe
                        ? pe.provider()
                        : providers.forModel(model).name();

        String code
                = e instanceof ProviderException pe
                        ? pe.provider() + "_unavailable"
                        : "server_error";

        return new RequestLog(
                null,
                ctx.userId(),
                ctx.apiKeyId(),
                provider,
                model,
                null,
                0,
                0,
                BigDecimal.ZERO,
                (int) Math.max(0, latencyMs),
                false,
                RequestLog.Status.ERROR,
                code,
                null,
                now()
        );
    }

    private RequestLog blockedLog(
            RequestBlockedException e,
            String model,
            RequestContext ctx) {

        return new RequestLog(
                null,
                ctx.userId(),
                ctx.apiKeyId(),
                null,
                model,
                null,
                0,
                0,
                BigDecimal.ZERO,
                0,
                false,
                RequestLog.Status.BLOCKED,
                null,
                e.reason(),
                now()
        );
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
