package com.fxbrief.analysis.dto;

import java.util.Map;

/**
 * Pre-computed structured analysis result for a single pair across all four
 * timeframes. Every numeric value comes from real candle data — none of it is
 * estimated by Claude (PRD §15, AI hallucination mitigation).
 *
 * <p>The five Claude-generated text fields ({@code setupStatus},
 * {@code shortReasoning}, {@code executiveReasoning}, {@code invalidationNote},
 * {@code fundamentalSummary}) are populated after the mega-call returns. They
 * are {@code null} on the value returned by {@code PairAnalysisEngine.analyze}
 * and filled in by {@code AnalysisEngine.withClaudeFields}.
 */
public record PairAnalysis(
        String pair,
        Map<String, MarketStructureView> structureByTimeframe,
        Zone activeZone,
        ConfidenceScoreView confidence,
        M15ConfirmationView m15Confirmation,
        SignalState signalState,
        TradePlan tradePlan,
        FundamentalAssessment fundamental,
        boolean htfConflict,
        boolean fundamentalConflict,
        String setupStatus,
        String shortReasoning,
        String executiveReasoning,
        String invalidationNote,
        String fundamentalSummary,
        String layer
) {

    public TechnicalStatus technicalStatus() {
        if (signalState == SignalState.CONFIRMED || signalState == SignalState.AWAITING_CONFIRMATION) {
            return htfConflict ? TechnicalStatus.CONFLICT_SETUP : TechnicalStatus.SETUP_READY;
        }
        if (signalState == SignalState.DETECTED && activeZone != null && !activeZone.invalidated()) {
            return TechnicalStatus.SETUP_READY;
        }
        Map<String, MarketStructureView> s = structureByTimeframe;
        if (s != null) {
            MarketStructureView d = s.get("D");
            if (d != null && d.bias() != MarketStructureView.MarketBias.RANGING) {
                return TechnicalStatus.TRENDING;
            }
        }
        return TechnicalStatus.NONE;
    }

    public enum TechnicalStatus { TRENDING, SETUP_READY, CONFLICT_SETUP, NONE }
}
