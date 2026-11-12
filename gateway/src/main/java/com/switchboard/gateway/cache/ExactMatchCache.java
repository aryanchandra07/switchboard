package com.switchboard.gateway.cache;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

@Service
public class ExactMatchCache {

    private final ReactiveStringRedisTemplate redis;
    private final Duration ttl;

    public ExactMatchCache(
            ReactiveStringRedisTemplate redis,
            @Value("${switchboard.cache.ttl-seconds:3600}") long ttlSeconds) {

        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Mono<String> get(String key) {
        return redis.opsForValue().get(key);
    }

    public Mono<Boolean> put(String key, String value) {
        return redis.opsForValue().set(key, value, ttl);
    }

    public Mono<Boolean> delete(String key) {
        return redis.delete(key).map(count -> count > 0);
    }
}
