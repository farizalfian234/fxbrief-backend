package com.fxbrief.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the ForexFactory economic calendar client. No API key —
 * the calendar file is served from a public CDN URL. Defaults to the canonical
 * faireconomy.media host; override in YAML if a mirror or backup host is
 * needed.
 *
 * Per DECISIONS D-043, we fetch this file at most once per day to stay well
 * inside ForexFactory's 2-downloads-per-5-minutes rate limit, with an
 * application-startup hook that skips the fetch when the local cache is less
 * than {@code startupRefreshThresholdHours} hours old.
 */
@ConfigurationProperties(prefix = "fxbrief.forex-factory")
public record ForexFactoryProperties(
        String baseUrl,
        String userAgent,
        int connectTimeoutMs,
        int readTimeoutMs,
        int startupRefreshThresholdHours
) {}
