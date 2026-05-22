package com.fxbrief.analysis.dto;

import java.time.Instant;

public record EconomicEvent(
        String currency,
        String event,
        Instant date,
        String importance,
        String actual,
        String forecast,
        String previous
) {}
