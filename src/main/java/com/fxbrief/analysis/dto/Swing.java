package com.fxbrief.analysis.dto;

import java.time.Instant;

public record Swing(
        SwingType type,
        Instant timestamp,
        int barIndex,
        double price
) {

    public enum SwingType { HIGH, LOW }
}
