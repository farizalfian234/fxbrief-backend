package com.fxbrief.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fxbrief.claude")
public record ClaudeProperties(
        String apiKey,
        String baseUrl,
        String model,
        String apiVersion,
        int maxTokens,
        int connectTimeoutMs,
        int readTimeoutMs
) {}
