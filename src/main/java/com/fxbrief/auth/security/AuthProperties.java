package com.fxbrief.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fxbrief.auth")
public record AuthProperties(
        int emailVerificationTtlHours,
        int passwordResetTtlMinutes,
        int bcryptStrength
) {}
