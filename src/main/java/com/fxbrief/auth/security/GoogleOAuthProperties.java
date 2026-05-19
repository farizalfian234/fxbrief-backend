package com.fxbrief.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "fxbrief.oauth.google")
public record GoogleOAuthProperties(
        List<String> clientIds
) {}
