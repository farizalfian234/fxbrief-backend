package com.fxbrief.analysis.dto;

import java.util.List;

public record FundamentalAssessment(
        String currency,
        FundamentalBias bias,
        boolean highImpactThisWeek,
        List<EconomicEvent> events
) {

    public enum FundamentalBias { BULLISH, BEARISH, NEUTRAL }
}
