package com.fxbrief.analysis.dto;

public record TradePlan(
        Direction direction,
        double entryLow,
        double entryHigh,
        double takeProfit,
        double stopLoss
) {

    public enum Direction { LONG, SHORT }
}
