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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single-mega-call narrative generator. One Claude request produces five
 * named text fields for every analysed pair plus one report-level summary.
 *
 * <h2>Per PRD §8.4</h2>
 * Claude generates exactly the following per pair:
 * <ul>
 *   <li>{@code setupStatus} — 1 line</li>
 *   <li>{@code shortReasoning} — 1–2 sentences</li>
 *   <li>{@code executiveReasoning} — 3–5 sentences</li>
 *   <li>{@code invalidationNote} — 1 sentence</li>
 *   <li>{@code fundamentalSummary} — 2–3 sentences</li>
 * </ul>
 * Plus one report-level {@code summary} of at most 100 characters used for
 * the history table.
 *
 * <h2>Output shape</h2>
 * The prompt instructs Claude to emit a single JSON object directly, with
 * no assistant-message prefilling (some newer models reject prefill — see
 * DECISIONS D-052). The system prompt explicitly forbids markdown code
 * fences, but a defensive {@link #stripCodeFences} pass handles the case
 * where Claude wraps the response anyway.
 *
 * <pre>{@code
 * {
 *   "summary": "3 setups available — GBP/USD best opportunity",
 *   "pairs": [
 *     {
 *       "pair": "EUR/USD",
 *       "setupStatus": "...",
 *       "shortReasoning": "...",
 *       "executiveReasoning": "...",
 *       "invalidationNote": "...",
 *       "fundamentalSummary": "..."
 *     },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * Per DECISIONS D-045, individual pairs whose fields fail validation are
 * dropped from the result map; the caller falls back to per-pair generation
 * for those. The report summary's fallback is the Java composer (D-051).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MegaCallNarrativeService {

    public static final double MEGA_CALL_TEMPERATURE = 0.2;

    static final String SYSTEM_PROMPT = """
            You are FXBrief, a forex analysis writer. You will be given pre-computed structured \
            analysis for eight currency pairs. For each pair you must produce five named text \
            fields, plus one short report-level summary string.

            Do not invent, estimate, or recompute any numeric value, price level, percentage, \
            ratio, or score. Use only the numbers supplied in the structured input verbatim. If a \
            numeric value is not supplied for a pair, do not introduce one. Never perform \
            calculations — entry/TP/SL, R:R, confidence numbers, and zone bounds are all already \
            computed and present in the structured input as plain text.

            Per pair, generate:
              - setupStatus: ONE line. Human-readable signal state. Example: \
            "Confirmed short near H4 supply — low conviction due to zone penetration".
              - shortReasoning: ONE OR TWO sentences. Plain-English explanation of why this setup \
            is or is not actionable. This is what Free and Basic users see on the pair card.
              - executiveReasoning: THREE TO FIVE sentences. Readable narrative covering the \
            "so what" — what the setup is, why it has the confidence level it does, and what to \
            watch. Confidence-building but not an essay. No markdown, no bullet lists, no headings.
              - invalidationNote: ONE sentence. State the specific price-based invalidation \
            condition using only price levels supplied in the structured input. Example: \
            "Bullish bias invalidates if H4 closes above 1.16361."
              - fundamentalSummary: TWO OR THREE sentences. How the supplied high-impact \
            economic events affect this specific pair's setup. If no events apply, say so briefly.

            For the whole report, generate:
              - summary: AT MOST 100 characters total. A short headline for the history table. \
            Examples: "3 setups available — GBP/USD best opportunity" or \
            "Markets consolidating — no clear setups". No quotes around the summary.

            OUTPUT FORMAT — CRITICAL:
            Your entire response must be a single valid JSON object. The very first character of \
            your response must be the opening brace {. The very last character must be the \
            closing brace }. Do not wrap the JSON in markdown code fences. Do not include any \
            prose, preamble, commentary, or trailing text. Do not include the word "json" before \
            the object. Just the JSON object, nothing else.

            The JSON must have exactly this shape and field order:
            {
              "summary": "<= 100 chars",
              "pairs": [
                {
                  "pair": "<symbol>",
                  "setupStatus": "<text>",
                  "shortReasoning": "<text>",
                  "executiveReasoning": "<text>",
                  "invalidationNote": "<text>",
                  "fundamentalSummary": "<text>"
                }
              ]
            }

            The pairs array must contain one entry for every pair given in the input, in the same \
            order.
            """;

    private static final List<String> TIMEFRAMES = List.of("W", "D", "H4", "M15");

    private final ClaudeClient claudeClient;
    private final NarrativeValidator narrativeValidator;
    private final ObjectMapper objectMapper;

    /**
     * Single-call result: the per-pair fields keyed by symbol, and the
     * report-level summary string. Pairs that failed validation are
     * <b>absent</b> from {@code perPair} — the caller must handle them via
     * per-pair fallback.
     *
     * {@code summary} is {@code null} if Claude omitted it, returned blank,
     * or exceeded 100 characters; the caller substitutes the Java-composed
     * fallback in that case.
     */
    public record MegaCallResult(
            Map<String, PairNarrativeFields> perPair,
            String summary
    ) {}

    /**
     * Runs the mega-call. Returns successfully-validated fields per pair
     * plus the report summary. Pairs absent from the map need a per-pair
     * fallback at the caller.
     *
     * Throws {@link DomainException} only when the entire call fails (HTTP
     * error, JSON parse error on the outer envelope, etc.). Per-pair
     * validation failures do not throw — they manifest as absent entries
     * in the returned map.
     */
    public MegaCallResult generateAll(List<PairAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return new MegaCallResult(Map.of(), null);
        }

        String userPrompt = buildUserPrompt(analyses);

        // No assistant prefill — some Claude models reject prefilling
        // (DECISIONS D-052). The system prompt instructs Claude to emit a
        // pure JSON object directly.
        String response = claudeClient.generateNarrative(
                SYSTEM_PROMPT, userPrompt, null, MEGA_CALL_TEMPERATURE);

        String cleaned = stripCodeFences(response);

        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("Mega-call returned unparseable JSON ({} chars): {}",
                    cleaned == null ? 0 : cleaned.length(), e.getMessage());
            throw new DomainException(
                    com.fxbrief.common.constants.ErrorCodes.NARRATIVE_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Mega-call response is not valid JSON");
        }

        String summary = readSummary(root);

        Map<String, PairNarrativeFields> parsedByPair = parsePairFields(root, analyses);

        Map<String, PairAnalysis> analysesByPair = new LinkedHashMap<>();
        for (PairAnalysis a : analyses) {
            analysesByPair.put(a.pair(), a);
        }

        // Validation: anything that fails is removed from the map so the
        // engine's caller falls back to per-pair generation.
        List<String> invalidPairs = narrativeValidator.findInvalidPairs(parsedByPair, analysesByPair);
        for (String pair : invalidPairs) {
            parsedByPair.remove(pair);
        }

        log.info("Mega-call produced {} valid pair(s); {} invalid pair(s); summary={}",
                parsedByPair.size(), invalidPairs.size(),
                summary == null ? "<missing>" : "len=" + summary.length());
        return new MegaCallResult(parsedByPair, summary);
    }

    /**
     * Defensive strip of markdown code fences. The system prompt forbids
     * them, but Claude sometimes adds them anyway when emitting JSON. This
     * handles the common shapes: triple-backtick with or without a language
     * tag, with optional surrounding whitespace.
     */
    static String stripCodeFences(String response) {
        if (response == null) {
            return null;
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            // Drop the opening fence and optional language tag (e.g. ```json)
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            } else {
                trimmed = trimmed.substring(3);
            }
            // Drop the closing fence if present
            int closingFence = trimmed.lastIndexOf("```");
            if (closingFence >= 0) {
                trimmed = trimmed.substring(0, closingFence);
            }
            trimmed = trimmed.trim();
        }
        return trimmed;
    }

    private String readSummary(JsonNode root) {
        JsonNode summaryNode = root.path("summary");
        if (summaryNode.isMissingNode() || !summaryNode.isTextual()) {
            log.warn("Mega-call response missing summary field");
            return null;
        }
        String summary = summaryNode.asText();
        String reason = narrativeValidator.validateSummary(summary);
        if (reason != null) {
            log.warn("Mega-call summary failed validation: {}", reason);
            return null;
        }
        return summary;
    }

    private Map<String, PairNarrativeFields> parsePairFields(JsonNode root,
                                                              List<PairAnalysis> analyses) {
        Map<String, PairNarrativeFields> result = new LinkedHashMap<>();
        JsonNode pairsNode = root.path("pairs");
        if (!pairsNode.isArray()) {
            log.warn("Mega-call response missing pairs array");
            return result;
        }

        // The model is asked to return pairs in the same order it received them,
        // but we key the result map by the pair symbol field on each entry so
        // a swapped order is recoverable as long as the symbol field is correct.
        for (JsonNode entry : pairsNode) {
            JsonNode pairNode = entry.path("pair");
            if (!pairNode.isTextual()) {
                log.warn("Skipping pair entry missing pair symbol field");
                continue;
            }
            String pair = pairNode.asText();
            PairNarrativeFields fields = new PairNarrativeFields(
                    textOrNull(entry, "setupStatus"),
                    textOrNull(entry, "shortReasoning"),
                    textOrNull(entry, "executiveReasoning"),
                    textOrNull(entry, "invalidationNote"),
                    textOrNull(entry, "fundamentalSummary")
            );
            result.put(pair, fields);
        }
        return result;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode val = node.path(field);
        return val.isTextual() ? val.asText() : null;
    }

    /**
     * Builds the structured user prompt. Each pair is prefixed by its
     * symbol and a clear divider; every numeric value Claude is allowed to
     * cite is embedded as plain text. No JSON in the user message — keeps
     * the prompt cheap to tokenise and easy to debug.
     */
    String buildUserPrompt(List<PairAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();
        sb.append("You will produce one JSON response covering ALL of the following pairs ");
        sb.append("in the order given. Maintain pair identity precisely — do not swap, drop, ");
        sb.append("or add pairs.\n\n");
        sb.append("Pair order: ");
        for (int i = 0; i < analyses.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(analyses.get(i).pair());
        }
        sb.append("\n\n");

        for (PairAnalysis a : analyses) {
            sb.append("==================== ").append(a.pair()).append(" ====================\n");
            appendPairBlock(sb, a);
            sb.append('\n');
        }

        sb.append("\nNow produce the JSON object as specified. Start with { and end with }. ");
        sb.append("No code fences, no preamble, no trailing text.");
        return sb.toString();
    }

    private void appendPairBlock(StringBuilder sb, PairAnalysis a) {
        sb.append("Signal status: ").append(a.signalState()).append('\n');
        sb.append("Layer: ").append(a.layer()).append('\n');

        sb.append("\n--- Multi-timeframe structure ---\n");
        for (String tf : TIMEFRAMES) {
            MarketStructureView s = a.structureByTimeframe() == null
                    ? null : a.structureByTimeframe().get(tf);
            if (s != null) {
                sb.append(tf).append(": bias=").append(s.bias())
                        .append(" lastEvent=").append(s.lastEvent()).append('\n');
            }
        }

        Zone zone = a.activeZone();
        if (zone != null) {
            sb.append("\n--- Active zone (H4) ---\n");
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
        } else {
            sb.append("\n--- Active zone (H4) ---\nNone identified.\n");
        }

        M15ConfirmationView m15 = a.m15Confirmation();
        if (m15 != null) {
            sb.append("\n--- M15 entry confirmation ---\n");
            sb.append("Confirmed: ").append(m15.confirmed()).append('\n');
            sb.append("Liquidity sweep: ").append(m15.liquiditySweep()).append('\n');
            sb.append("M15 BOS: ").append(m15.m15Bos()).append('\n');
            sb.append("Engulfing displacement: ").append(m15.engulfingDisplacement()).append('\n');
            sb.append("CHOCH aligned with HTF: ").append(m15.chochAlignedWithHtf()).append('\n');
        }

        ConfidenceScoreView c = a.confidence();
        if (c != null) {
            sb.append("\n--- Confidence ---\n");
            sb.append("Score: ").append(c.score()).append(" (").append(c.level()).append(")\n");
            sb.append("Factors:\n");
            if (c.factors() != null) {
                c.factors().forEach(f ->
                        sb.append("  - ").append(f.label())
                                .append(": ").append(f.applied() ? "applied" : "not applied")
                                .append(" (").append(f.delta() >= 0 ? "+" : "")
                                .append(f.delta()).append(")\n"));
            }
        }

        TradePlan trade = a.tradePlan();
        if (trade != null) {
            sb.append("\n--- Trade plan ---\n");
            sb.append("Direction: ").append(trade.direction()).append('\n');
            sb.append("Entry zone: ").append(formatPrice(trade.entryLow()))
                    .append(" to ").append(formatPrice(trade.entryHigh())).append('\n');
            sb.append("Take profit: ").append(formatPrice(trade.takeProfit())).append('\n');
            sb.append("Stop loss: ").append(formatPrice(trade.stopLoss())).append('\n');
        }

        FundamentalAssessment f = a.fundamental();
        if (f != null) {
            sb.append("\n--- Fundamental layer ---\n");
            sb.append("Currency: ").append(f.currency()).append('\n');
            sb.append("Bias: ").append(f.bias()).append('\n');
            sb.append("High impact this week: ").append(f.highImpactThisWeek()).append('\n');
            if (f.events() != null && !f.events().isEmpty()) {
                sb.append("Top events:\n");
                int max = Math.min(5, f.events().size());
                for (int i = 0; i < max; i++) {
                    EconomicEvent e = f.events().get(i);
                    sb.append("  - ").append(e.event())
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
    }

    private String formatPrice(double price) {
        return String.format(Locale.US, "%.5f", price);
    }
}
