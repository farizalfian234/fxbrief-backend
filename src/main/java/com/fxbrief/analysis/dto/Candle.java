package com.fxbrief.analysis.dto;

import java.time.Instant;

/**
 * Single OHLCV bar. Volume is optional for forex pairs (Twelve Data returns 0)
 * and meaningful for XAU/USD.
 */
public record Candle(
        Instant timestamp,
        double open,
        double high,
        double low,
        double close,
        double volume
) {

    public boolean isBullish() {
        return close > open;
    }

    public boolean isBearish() {
        return close < open;
    }

    public double body() {
        return Math.abs(close - open);
    }

    public double range() {
        return high - low;
    }

    public double upperWick() {
        return high - Math.max(open, close);
    }

    public double lowerWick() {
        return Math.min(open, close) - low;
    }
}
