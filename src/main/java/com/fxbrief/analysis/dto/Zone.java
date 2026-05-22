package com.fxbrief.analysis.dto;

import java.time.Instant;
import java.util.List;

public record Zone(
        ZoneKind kind,
        double low,
        double high,
        Instant formedAt,
        int formedBarIndex,
        int freshnessScore,
        FreshnessState freshnessState,
        double maxPenetrationRatio,
        PenetrationDepth penetrationDepth,
        List<Double> fibonacciLevelsInside,
        boolean invalidated,
        String invalidationReason
) {

    public enum ZoneKind { SUPPLY, DEMAND }

    public enum FreshnessState {
        FRESH,
        WICK_TOUCHED,
        BODY_TOUCHED,
        WEAKENED,
        INVALIDATED
    }

    /**
     * Penetration-depth category per PRD §7.3. Computed from the raw
     * {@link #maxPenetrationRatio} against the configured shallow/deep
     * thresholds in {@code AnalysisProperties.ZonePenetration}.
     *
     * <ul>
     *   <li>{@link #SHALLOW} — penetration below the shallow threshold.
     *       Zone integrity intact; expected market behaviour.</li>
     *   <li>{@link #MODERATE} — penetration between the thresholds.
     *       Zone tested but not significantly weakened.</li>
     *   <li>{@link #DEEP} — penetration at or above the deep threshold.
     *       Zone significantly weakened; applies a confidence penalty.</li>
     * </ul>
     */
    public enum PenetrationDepth { SHALLOW, MODERATE, DEEP }

    public double midpoint() {
        return (high + low) / 2.0;
    }

    public double depth() {
        return high - low;
    }
}
