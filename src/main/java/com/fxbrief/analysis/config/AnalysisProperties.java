package com.fxbrief.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * SMC analysis tunables bound from {@code fxbrief.analysis.*} in application.yml.
 * All thresholds and weights for market-structure detection, zone freshness scoring,
 * Fibonacci confluence, M15 entry confirmation, weighted confidence scoring, and the
 * signal lifecycle expiry window live here so the algorithm can be retuned without a
 * recompile.
 */
@ConfigurationProperties(prefix = "fxbrief.analysis")
public record AnalysisProperties(
        SignificantSwing significantSwing,
        ZoneFreshness zoneFreshness,
        ZonePenetration zonePenetration,
        Fibonacci fibonacci,
        M15Confirmation m15Confirmation,
        ConfidenceWeights confidenceWeights,
        SignalLifecycle signalLifecycle,
        Fundamental fundamental,
        ShadowNarrativeLogging shadowNarrativeLogging
) {

    /**
     * Shadow QA logging toggle (DECISIONS D-046). When enabled, the engine
     * runs the per-pair narrative path alongside the mega-call and stores
     * both into {@code narrative_qa_log} for divergence measurement. Doubles
     * Claude cost while enabled — intended for the first 2-4 weeks of
     * production then turned off.
     */
    public record ShadowNarrativeLogging(boolean enabled) {}

    /**
     * Displacement filter for "significant" swings. A swing is significant only if
     * the move from the swing's pivot to the next opposing pivot exceeds
     * {@code minDisplacementAtrMultiple} times the ATR over {@code atrPeriod} bars,
     * and the swing must be at least {@code lookbackBars} bars away from prior pivots
     * to filter out small choppy structure.
     */
    public record SignificantSwing(
            int atrPeriod,
            double minDisplacementAtrMultiple,
            int lookbackBars
    ) {}

    /**
     * Freshness scoring weights per PRD §7.3.
     */
    public record ZoneFreshness(
            int neverRetestedScore,
            int wickTouchScore,
            int oneBodyCloseInsideScore,
            int multipleCandlesInsideScore,
            int invalidationThreshold
    ) {}

    /**
     * Penetration-depth thresholds per PRD §7.3. Values are 0.0-1.0 ratios of how
     * far price pushed into the zone before reversing.
     */
    public record ZonePenetration(
            double shallowMaxRatio,
            double deepMinRatio
    ) {}

    /**
     * Fibonacci confluence levels per PRD §7.4. Only used inside a valid S&D zone —
     * never standalone.
     */
    public record Fibonacci(
            List<Double> strongTrendLevels,
            List<Double> deepPullbackLevels,
            double levelMatchToleranceRatio
    ) {}

    /**
     * M15 entry confirmation thresholds per PRD §7.5.
     */
    public record M15Confirmation(
            double engulfingMinBodyRatio,
            double displacementMinAtrMultiple,
            int sweepLookbackBars
    ) {}

    /**
     * Weighted confidence scoring weights per PRD §7.6.
     * The {@code deeplyPenetratedZone} weight is a negative adjustment applied
     * when the active zone's penetration depth crosses
     * {@code ZonePenetration.deepMinRatio} per PRD §7.3 — a deeply-penetrated
     * zone has been materially weakened and the confidence score should
     * reflect that.
     */
    public record ConfidenceWeights(
            int htfStructureAligned,
            int freshZone,
            int m15Bos,
            int liquiditySweep,
            int fibonacciConfluence,
            int candlestickPattern,
            int fundamentalConflict,
            int deeplyPenetratedZone,
            int lowMaxScore,
            int mediumMaxScore
    ) {}

    /**
     * Signal lifecycle expiry per PRD §7.7.
     */
    public record SignalLifecycle(
            int awaitingConfirmationExpiryDays
    ) {}

    /**
     * Fundamental analysis configuration. Events at or above {@code highImpactLevel}
     * count as the "major event" that may apply the fundamental-conflict penalty
     * per PRD §7.10.
     */
    public record Fundamental(
            String highImpactLevel
    ) {}
}
