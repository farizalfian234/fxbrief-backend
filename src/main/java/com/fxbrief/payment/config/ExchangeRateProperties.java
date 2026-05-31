package com.fxbrief.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * USD/IDR exchange-rate fetch configuration. The source (open.er-api.com) needs no API
 * key. {@code startupRefreshThresholdHours} bounds how stale the cached rate may be
 * before a startup fetch is triggered, mirroring the ForexFactory startup-freshness
 * pattern.
 */
@ConfigurationProperties(prefix = "fxbrief.exchange-rate")
public record ExchangeRateProperties(
        String baseUrl,
        int startupRefreshThresholdHours,
        int connectTimeoutMs,
        int readTimeoutMs
) {}
