package com.fxbrief.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend and email-sender configuration bound from {@code fxbrief.notification.*}.
 * {@code apiKey}, {@code fromEmail}, and {@code adminEmail} come from environment
 * variables; {@code fromName} has a sensible default in application.yml.
 */
@ConfigurationProperties(prefix = "fxbrief.notification")
public record NotificationProperties(
        String apiKey,
        String fromEmail,
        String fromName,
        String adminEmail
) {}
