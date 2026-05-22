package com.fxbrief.analysis.entity;

public enum Timeframe {

    W("W", "1week", 1),
    D("D", "1day", 2),
    H4("H4", "4h", 3),
    M15("M15", "15min", 4);

    private final String code;
    private final String twelveDataInterval;
    private final int priority;

    Timeframe(String code, String twelveDataInterval, int priority) {
        this.code = code;
        this.twelveDataInterval = twelveDataInterval;
        this.priority = priority;
    }

    public String getCode() {
        return code;
    }

    public String getTwelveDataInterval() {
        return twelveDataInterval;
    }

    /**
     * Lower value = higher timeframe priority (PRD §7.1).
     */
    public int getPriority() {
        return priority;
    }
}
