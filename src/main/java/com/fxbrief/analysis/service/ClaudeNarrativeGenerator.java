package com.fxbrief.analysis.service;

import com.fxbrief.analysis.client.ClaudeClient;
import com.fxbrief.analysis.dto.ConfidenceScoreView;
import com.fxbrief.analysis.dto.EconomicEvent;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.M15ConfirmationView;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.TradePlan;
import com.fxbrief.analysis.dto.Zone;
import com.fxbrief.common.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Single-pair Claude narrative generator. Used in two situations:
 *
 * <ol>
 *   <li>Per-pair fallback when the mega-call's validator flags one or more
 *       pairs as invalid (DECISIONS D-045).</li>
 *   <li>Shadow QA path when {@code shadow-narrative-logging.enabled} is true
 *       (DECISIONS D-046).</li>
 * </ol>
 *
 * Per DECISIONS D-042, narratives are plan-agnostic at generation time:
 * every pair receives the full Premium-depth narrative regardless of which
 * subscription tier triggered the report.
 *
 * The system prompt forbids Claude from estimating any numeric value. All
 * prices, levels and scores are pre-computed and embedded in the user prompt
 * as plain text (PRD §11.3 step 7, §15 hallucination mitigation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeNarrativeGenerator {

    static final String NARRATIVE_FALLBACK =
            "Narrative unavailable — see structured analysis below.";

    static final String SYSTEM_PROMPT = """
            You are FXBrief, a forex analysis writer. Generate an in-depth, plain-English Smart \
            Money Concept narrative from the structured analysis provided. Do not invent, \
            estimate, or recompute any numeric value, price level, percentage, ratio or score — \
            use only the numbers supplied in the user prompt verbatim. If a value is not supplied, \
            do not introduce one. Begin the narrative with the pair symbol exactly as given. \
            Cover, in order: multi-timeframe structure (W, D, H4, M15), the active supply/demand \
            zone with its freshness state, penetration and Fibonacci confluence, the M15 \
            confirmation status with whichever sub-signals fired, the weighted confidence score \
            with contributing factors, the signal lifecycle state, and the fundamental layer with \
            high-impact events. Keep paragraphs short; do not use markdown headings or bullet \
            lists. Do not include disclaimers or boilerplate.
            """;

    private final ClaudeClient claudeClient;

    public String generate(PairAnalysis analysis) {
        if (analysis == null) {
            return NARRATIVE_FALLBACK;
        }
        try {
            String userPrompt = buildPrompt(analysis);
            return claudeClient.generateNarrative(SYSTEM_PROMPT, userPrompt);
        } catch (DomainException e) {
            log.warn("Claude narrative generation failed for {}: code={} message={}",
                    analysis.pair(), e.getCode(), e.getMessage());
            return NARRATIVE_FALLBACK;
        } catch (Exception e) {
            log.warn("Claude narrative generation threw unexpectedly for {}: {}",
                    analysis.pair(), e.getMessage());
            return NARRATIVE_FALLBACK;
        }
    }

    String buildPrompt(PairAnalysis a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pair: ").append(a.pair()).append('\n');
        sb.append("Signal status: ").append(a.signalState()).append('\n');
        sb.append("Layer: ").append(a.layer()).append('\n').append('\n');

        sb.append("=== Multi-timeframe structure ===\n");
        for (String tf : List.of("W", "D", "H4", "M15")) {
            MarketStructureView s = a.structureByTimeframe().get(tf);
            if (s != null) {
                sb.append(tf).append(": bias=").append(s.bias())
                        .append(" lastEvent=").append(s.lastEvent()).append('\n');
            }
        }
        sb.append('\n');

        Zone zone = a.activeZone();
        if (zone != null) {
            sb.append("=== Active zone (H4) ===\n");
            sb.append("Kind: ").append(zone.kind()).append('\n');
            sb.append("Range: ").append(formatPrice(zone.low()))
                    .append(" to ").append(formatPrice(zone.high())).append('\n');
            sb.append("Formed at: ").append(zone.formedAt()).append('\n');
            sb.append("Freshness: ").append(zone.freshnessState())
                    .append(" (score ").append(zone.freshnessScore()).append(")\n");
            sb.append("Max penetration ratio: ")
                    .append(String.format(Locale.US, "%.2f", zone.maxPenetrationRatio())).append('\n');
            if (zone.penetrationDepth() != null) {
                sb.append("Penetration depth: ").append(zone.penetrationDepth()).append('\n');
            }
            if (zone.fibonacciLevelsInside() != null && !zone.fibonacciLevelsInside().isEmpty()) {
                sb.append("Fibonacci confluence levels inside zone: ")
                        .append(zone.fibonacciLevelsInside()).append('\n');
            } else {
                sb.append("Fibonacci confluence: none\n");
            }
            if (zone.invalidated()) {
                sb.append("Invalidated: ").append(zone.invalidationReason()).append('\n');
            }
            sb.append('\n');
        } else {
            sb.append("=== Active zone (H4) ===\nNone identified.\n\n");
        }

        M15ConfirmationView m15 = a.m15Confirmation();
        if (m15 != null) {
            sb.append("=== M15 entry confirmation ===\n");
            sb.append("Confirmed: ").append(m15.confirmed()).append('\n');
            sb.append("Liquidity sweep: ").append(m15.liquiditySweep()).append('\n');
            sb.append("M15 BOS: ").append(m15.m15Bos()).append('\n');
            sb.append("Engulfing displacement: ").append(m15.engulfingDisplacement()).append('\n');
            sb.append("CHOCH aligned with HTF: ").append(m15.chochAlignedWithHtf()).append('\n');
            sb.append('\n');
        }

        ConfidenceScoreView c = a.confidence();
        if (c != null) {
            sb.append("=== Confidence ===\n");
            sb.append("Score: ").append(c.score()).append(" (").append(c.level()).append(")\n");
            sb.append("Factors:\n");
            if (c.factors() != null) {
                c.factors().forEach(f ->
                        sb.append(" - ").append(f.label())
                                .append(": ").append(f.applied() ? "applied" : "not applied")
                                .append(" (").append(f.delta() >= 0 ? "+" : "")
                                .append(f.delta()).append(")\n"));
            }
            sb.append('\n');
        }

        TradePlan trade = a.tradePlan();
        if (trade != null) {
            sb.append("=== Trade plan ===\n");
            sb.append("Direction: ").append(trade.direction()).append('\n');
            sb.append("Entry zone: ").append(formatPrice(trade.entryLow()))
                    .append(" to ").append(formatPrice(trade.entryHigh())).append('\n');
            sb.append("Take profit: ").append(formatPrice(trade.takeProfit())).append('\n');
            sb.append("Stop loss: ").append(formatPrice(trade.stopLoss())).append('\n');
            sb.append('\n');
        }

        FundamentalAssessment f = a.fundamental();
        if (f != null) {
            sb.append("=== Fundamental layer ===\n");
            sb.append("Currency: ").append(f.currency()).append('\n');
            sb.append("Bias: ").append(f.bias()).append('\n');
            sb.append("High impact this week: ").append(f.highImpactThisWeek()).append('\n');
            if (f.events() != null && !f.events().isEmpty()) {
                sb.append("Top events:\n");
                int max = Math.min(5, f.events().size());
                for (int i = 0; i < max; i++) {
                    EconomicEvent e = f.events().get(i);
                    sb.append(" - ").append(e.event())
                            .append(" (importance=").append(e.importance()).append(")");
                    if (e.actual() != null || e.forecast() != null) {
                        sb.append(" actual=").append(e.actual())
                                .append(" forecast=").append(e.forecast());
                    }
                    sb.append('\n');
                }
            }
        }
        if (a.htfConflict()) {
            sb.append("\nNote: weekly and daily bias conflict.\n");
        }
        if (a.fundamentalConflict()) {
            sb.append("Note: high-impact economic events this week affect this pair.\n");
        }
        return sb.toString();
    }

    private String formatPrice(double price) {
        return String.format(Locale.US, "%.5f", price);
    }
}
