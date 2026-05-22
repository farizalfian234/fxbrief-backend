package com.fxbrief.analysis.dto;

import java.util.List;

public record MarketStructureView(
        MarketBias bias,
        StructureEvent lastEvent,
        List<Swing> recentSwings
) {

    public enum MarketBias { BULLISH, BEARISH, RANGING }

    public enum StructureEvent { BOS_BULLISH, BOS_BEARISH, CHOCH_BULLISH, CHOCH_BEARISH, NONE }
}
