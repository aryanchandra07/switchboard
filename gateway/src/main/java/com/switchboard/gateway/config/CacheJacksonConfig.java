package com.switchboard.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheJacksonConfig {

    @Bean
    public ObjectMapper cacheObjectMapper() {
        return new ObjectMapper();
    }
}
