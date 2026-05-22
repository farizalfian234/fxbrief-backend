package com.fxbrief.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fxbrief.twelve-data")
public record TwelveDataProperties(
        String apiKey,
        String baseUrl,
        int outputSize,
        int connectTimeoutMs,
        int readTimeoutMs
) {}
