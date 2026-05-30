package com.fxbrief.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the weekly market summary. {@code lookbackDays} is the number
 * of forex market days aggregated into one recap (Monday–Friday by default).
 */
@ConfigurationProperties(prefix = "fxbrief.weekly-summary")
public record WeeklySummaryProperties(
        int lookbackDays
) {
    public WeeklySummaryProperties {
        if (lookbackDays <= 0) {
            lookbackDays = 5;
        }
    }
}
