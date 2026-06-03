package com.fxbrief.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.client.ClaudeClient;
import com.fxbrief.analysis.dto.ConfidenceScoreView;
import com.fxbrief.analysis.dto.EconomicEvent;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.M15ConfirmationView;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.PairNarrativeFields;
import com.fxbrief.analysis.dto.TradePlan;
import com.fxbrief.analysis.dto.Zone;
import com.fxbrief.common.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Single-pair Claude narrative generator. Returns the same five named text
 * fields as the mega-call (PRD §8.4). Used in two situations:
 *
 * <ol>
 *   <li>Per-pair fallback when the mega-call's validator flags one or more
 *       pairs as invalid (DECISIONS D-045).</li>
 *   <li>Shadow QA path when {@code shadow-narrative-logging.enabled} is true
 *       (DECISIONS D-046).</li>
 * </ol>
 *
 * Per DECISIONS D-042, narratives are plan-agnostic at generation time:
 * every pair receives the full Premium-depth content regardless of which
 * subscription tier triggered the report.
 *
 * The system prompt forbids Claude from estimating any numeric value. All
 * prices, levels and scores are pre-computed and embedded in the user prompt
 * as plain text (PRD §11.3 step 7, §15 hallucination mitigation).
 *
 * Like the mega-call (D-052), this generator no longer uses assistant-
 * message prefilling. The prompt instructs Claude to emit a pure JSON
 * object; a defensive code-fence stripper runs before parsing.
 *
 * This generator does <b>not</b> produce the report-level summary — that
 * field belongs to the mega-call only. When the mega-call is unavailable
 * for the whole report, the summary falls back to the Java composer
 * (DECISIONS D-051).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeNarrativeGenerator {

    public static final double PER_PAIR_TEMPERATURE = 0.2;

    static final String SYSTEM_PROMPT = """
            You are FXBrief, a forex analysis writer. You will be given pre-computed structured \
            analysis for a single currency pair. Produce a JSON object with exactly these six \
            fields, no more, no less:
              - pair: the pair symbol exactly as given

              - setupStatus: ONE line. Human-readable signal state.

              - shortReasoning: EXACTLY ONE sentence, MAXIMUM 120 CHARACTERS. \
            No price levels, no zone numbers, no percentages, no R:R figures — those belong \
            in the structured data. One punchy reason why the setup is or is not actionable. \
            Example: "Confirmed short at H4 supply with M15 BOS — low conviction due to deep \
            penetration and fundamental conflict."

              - executiveReasoning: EXACTLY THREE short sentences. No price levels, no zone \
            numbers, no percentages — the structured data carries those. Each sentence is \
            concise; no run-ons, no listing, no semicolons stringing clauses together. \
            Sentence 1: what is happening and the key reason. Sentence 2: the main risk or \
            concern. Sentence 3: what to watch for next.

              - invalidationNote: ONE sentence. State the specific price-based invalidation \
            condition using only price levels supplied in the structured input.

              - fundamentalSummary: EXACTLY TWO short sentences. No long lists. Sentence 1: \
            the one or two most relevant economic events this week and their potential \
            impact — not a roll-call of every supplied event. Sentence 2: how this affects \
            the specific setup direction for this pair.

            Do not invent, estimate, or recompute any numeric value, price level, percentage, \
            ratio, or score. Use only the numbers supplied in the structured input verbatim.

            OUTPUT FORMAT — CRITICAL:
            Your entire response must be a single valid JSON object. The very first character of \
            your response must be the opening brace {. The very last character must be the \
            closing brace }. Do not wrap the JSON in markdown code fences. Do not include any \
            prose, preamble, commentary, or trailing text. Just the JSON object, nothing else.
            """;

    private static final List<String> TIMEFRAMES = List.of("W", "D", "H4", "M15");

    private final ClaudeClient claudeClient;
    private final ObjectMapper objectMapper;

    public PairNarrativeFields generate(PairAnalysis analysis) {
        if (analysis == null) {
            return PairNarrativeFields.fallback();
        }
        try {
            String userPrompt = buildPrompt(analysis);
            // No assistant prefill — see DECISIONS D-052.
            String response = claudeClient.generateNarrative(
                    SYSTEM_PROMPT, userPrompt, null, PER_PAIR_TEMPERATURE);
            return parse(response, analysis.pair());
        } catch (DomainException e) {
            log.warn("Per-pair Claude call failed for {}: code={} message={}",
                    analysis.pair(), e.getCode(), e.getMessage());
            return PairNarrativeFields.fallback();
        } catch (Exception e) {
            log.warn("Per-pair Claude call threw unexpectedly for {}: {}",
                    analysis.pair(), e.getMessage());
            return PairNarrativeFields.fallback();
        }
    }

    private PairNarrativeFields parse(String response, String pair) {
        try {
            String cleaned = MegaCallNarrativeService.stripCodeFences(response);
            JsonNode root = objectMapper.readTree(cleaned);
            return new PairNarrativeFields(
                    textOrFallback(root, "setupStatus"),
                    textOrFallback(root, "shortReasoning"),
                    textOrFallback(root, "executiveReasoning"),
                    textOrFallback(root, "invalidationNote"),
                    textOrFallback(root, "fundamentalSummary")
            );
        } catch (Exception e) {
            log.warn("Per-pair response unparseable for {}: {}", pair, e.getMessage());
            return PairNarrativeFields.fallback();
        }
    }

    private String textOrFallback(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isTextual() && !node.asText().isBlank()) {
            return node.asText();
        }
        return "Analysis unavailable — see structured data below.";
    }

    String buildPrompt(PairAnalysis a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pair: ").append(a.pair()).append('\n');
        sb.append("Signal status: ").append(a.signalState()).append('\n');
        sb.append("Layer: ").append(a.layer()).append('\n').append('\n');

        sb.append("=== Multi-timeframe structure ===\n");
        for (String tf : TIMEFRAMES) {
            MarketStructureView s = a.structureByTimeframe() == null
                    ? null : a.structureByTimeframe().get(tf);
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

        sb.append("\nNow produce the JSON object as specified. Start with { and end with }. ");
        sb.append("No code fences, no preamble, no trailing text.");
        return sb.toString();
    }

    private String formatPrice(double price) {
        return String.format(Locale.US, "%.5f", price);
    }
}
