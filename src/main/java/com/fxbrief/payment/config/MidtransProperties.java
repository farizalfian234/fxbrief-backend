package com.fxbrief.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Midtrans configuration. {@code production} drives the API base URLs and is the only
 * value that must change to move from sandbox to production — no code change or
 * redeployment is required beyond the environment flip.
 */
@ConfigurationProperties(prefix = "fxbrief.midtrans")
public record MidtransProperties(
        String serverKey,
        String clientKey,
        boolean production,
        String sandboxSnapBaseUrl,
        String productionSnapBaseUrl,
        int connectTimeoutMs,
        int readTimeoutMs
) {

    public String snapBaseUrl() {
        return production ? productionSnapBaseUrl : sandboxSnapBaseUrl;
    }
}
