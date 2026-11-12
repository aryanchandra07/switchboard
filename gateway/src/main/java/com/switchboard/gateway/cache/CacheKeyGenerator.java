package com.switchboard.gateway.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.switchboard.gateway.api.dto.ChatCompletionRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class CacheKeyGenerator {

    private final ObjectMapper objectMapper;

    public CacheKeyGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(
            ChatCompletionRequest request,
            String model,
            String scope) {

        try {
            String normalizedModel = model.trim();

            String payload = objectMapper.writeValueAsString(
                    new CacheInput(
                            scope,
                            normalizedModel,
                            request.messages(),
                            request.maxTokens()
                    )
            );

            MessageDigest digest
                    = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    payload.getBytes(StandardCharsets.UTF_8)
            );

            return "switchboard:exact:v1:"
                    + HexFormat.of().formatHex(hash);

        } catch (JsonProcessingException
                | NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "Failed to generate cache key", e
            );
        }
    }

    private record CacheInput(
            String scope,
            String model,
            Object messages,
            Object maxTokens
    ) {
    }
}
