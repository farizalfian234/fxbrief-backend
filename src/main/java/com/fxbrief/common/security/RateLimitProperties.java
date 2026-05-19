package com.fxbrief.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "fxbrief.rate-limit")
public record RateLimitProperties(
        int windowSeconds,
        Map<String, Integer> endpointLimits
) {}
