package com.fxbrief.analysis.dto;

/**
 * Trimmed best-pair shape returned to Free and Basic plan users.
 *
 * <p>Carries exactly the fields the Free/Basic UI renders across two
 * sections of PRD §8.2:
 *
 * <h2>Summary Section (§8.2 Section 1)</h2>
 * <ul>
 *   <li>Best Pair name → {@code pair}</li>
 *   <li>Market Bias → {@code dailyBias}
 *       (from {@code structureByTimeframe.D.bias})</li>
 *   <li>Setup Quality → {@code confidenceLevel}
 *       (from {@code confidence.level})</li>
 *   <li>Major News Risk → {@code majorNewsRisk}
 *       (from {@code fundamental.highImpactThisWeek})</li>
 *   <li>Active Opportunity Window → {@code signalState}
 *       (frontend maps CONFIRMED → "Active Setup", AWAITING_CONFIRMATION →
 *       "Setup Forming", DETECTED → "Early Signal",
 *       EXPIRED/INVALIDATED → "No Active Setup")</li>
 * </ul>
 *
 * <h2>Best Pair Card (§8.2 Section 2)</h2>
 * <ul>
 *   <li>Section 1 — {@code pair}, {@code dailyBias}, {@code confidenceLevel}</li>
 *   <li>Section 2 — {@code setupStatus}</li>
 *   <li>Section 3 — {@code tradePlan} (entry, SL, TP; R:R computed by frontend)</li>
 *   <li>Section 4 — {@code shortReasoning}</li>
 * </ul>
 *
 * No other fields are exposed. {@code executiveReasoning},
 * {@code invalidationNote}, {@code fundamentalSummary}, the full
 * {@code structureByTimeframe} map, {@code activeZone}, {@code m15Confirmation},
 * {@code confidence.factors}, {@code fundamental.events} — none are present
 * on the wire. A Basic user inspecting the network response sees only what
 * their UI renders (D-055).
 *
 * The {@code signalState} field also drives the "View Full Analysis" button
 * state (locked when no actionable signal, upsell when actionable).
 */
public record BestPairView(
        String pair,
        MarketStructureView.MarketBias dailyBias,
        ConfidenceScoreView.ConfidenceLevel confidenceLevel,
        boolean majorNewsRisk,
        SignalState signalState,
        String setupStatus,
        String shortReasoning,
        TradePlan tradePlan
) {}
