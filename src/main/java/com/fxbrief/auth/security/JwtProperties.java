package com.fxbrief.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fxbrief.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        int expirationHours
) {}
