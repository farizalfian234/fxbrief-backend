package com.fxbrief.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fxbrief.frontend")
public record FrontendProperties(
        String baseUrl
) {}
