package com.fxbrief.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.client.ClaudeClient;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.common.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates narratives for all pairs in a single Claude API call ("mega-call").
 *
 * <h2>Hallucination mitigation stack</h2>
 * Per DECISIONS D-045, the mega-call layers eight defences against
 * cross-pair contamination and numeric fabrication:
 * <ol>
 *   <li>Structured JSON output schema enforced via system prompt.</li>
 *   <li>XML-fenced {@code <pair_data id="EUR/USD">...</pair_data>} blocks
 *       in the user prompt — strong attention anchors per pair.</li>
 *   <li>Pair-echo requirement: each narrative must begin with its pair
 *       symbol as plain text (validator enforces).</li>
 *   <li>Numeric value validation: extracted price-like tokens cross-checked
 *       against the structured input for that pair.</li>
 *   <li>Assistant prefilling: response is prefilled with the JSON opening
 *       and first pair, forcing schema commitment before generation.</li>
 *   <li>Low temperature (0.2): reduces creative drift on a structured
 *       rewriting task.</li>
 *   <li>Order parity: pairs in the response array must appear in the same
 *       order they were given in the prompt.</li>
 *   <li>Explicit system rules: "use only the data inside the matching
 *       block; do not cross-reference between blocks."</li>
 * </ol>
 *
 * <h2>Failure paths</h2>
 * <ul>
 *   <li>JSON parse failure → one automatic retry with stricter prompt
 *       framing → on second failure, throws so the caller can fall back to
 *       per-pair calls for every pair.</li>
 *   <li>Schema validation failure (missing pair, wrong count) → throws,
 *       same caller fallback.</li>
 *   <li>Per-pair validation failure (pair echo missing, numeric token not
 *       in source) → that pair's narrative is dropped from the result map;
 *       caller substitutes a per-pair call for the missing one.</li>
 * </ul>
 *
 * The caller ({@link AnalysisEngine}) decides what to do with the result —
 * this service is purely the mega-call mechanism plus validation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MegaCallNarrativeService {

    private static final double MEGA_CALL_TEMPERATURE = 0.2;

    private static final String SYSTEM_PROMPT = """
            You are FXBrief, a forex analysis writer. You will receive structured Smart Money \
            Concept analysis for 8 forex pairs and must return one in-depth, plain-English \
            narrative per pair.

            Strict rules:
            1. Use ONLY the data inside each pair's <pair_data> block when writing that pair's \
               narrative. Do not reference or combine data across blocks.
            2. Do not invent, estimate, or recompute any numeric value, price level, percentage, \
               ratio or score. Use only the numbers supplied verbatim. If a value is not present \
               in a pair's block, do not introduce one for that pair.
            3. Each narrative must begin with the pair symbol exactly as given (e.g. "EUR/USD ").
            4. Cover, in order per narrative: multi-timeframe structure (W, D, H4, M15), the \
               active supply/demand zone with its freshness and penetration, the M15 \
               confirmation, the weighted confidence score with contributing factors, the signal \
               lifecycle state, and the fundamental layer.
            5. Keep each narrative concise (about 120-180 words). No markdown headings or bullet \
               lists. No disclaimers or boilerplate.
            6. Return ONLY a JSON object matching this schema, with the pairs in the same order \
               they appear in the prompt:
               {"narratives":[{"pair":"<symbol>","narrative":"..."}, ...]}
            """;

    private static final String JSON_PREFILL = "{\"narratives\":[{\"pair\":\"";

    private final ClaudeClient claudeClient;
    private final ObjectMapper objectMapper;
    private final NarrativeValidator validator;

    /**
     * Generates narratives for all pairs in one call.
     *
     * @return map of pair symbol -> narrative text, only including pairs whose
     *         narrative passed validation. Pairs that failed validation are
     *         absent from the map; the caller is expected to substitute a
     *         per-pair fallback call for each missing pair.
     * @throws DomainException when the mega-call itself fails irrecoverably
     *         (Claude error, JSON parse failure after retry, schema mismatch).
     *         The caller falls back to per-pair calls for every pair.
     */
    public Map<String, String> generateAll(List<PairAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return Map.of();
        }

        String userPrompt = buildPrompt(analyses);
        String firstPair = analyses.get(0).pair();
        String prefill = JSON_PREFILL + firstPair + "\",\"narrative\":\"";

        String response;
        try {
            response = claudeClient.generateNarrative(
                    SYSTEM_PROMPT, userPrompt, prefill, MEGA_CALL_TEMPERATURE);
        } catch (DomainException e) {
            log.warn("Mega-call Claude invocation failed: code={} message={}", e.getCode(), e.getMessage());
            throw e;
        }

        Map<String, String> parsed = tryParse(response, analyses);
        if (parsed != null) {
            return validateAndFilter(parsed, analyses);
        }

        log.info("Mega-call JSON parse failed; retrying once with strict re-prompt");
        String retryPrompt = userPrompt + """


                Your previous response was not valid JSON or did not match the required schema. \
                Return only the JSON object: {"narratives":[{"pair":"<symbol>","narrative":"..."}, ...]} \
                with one entry per pair in the same order given.
                """;
        String retry;
        try {
            retry = claudeClient.generateNarrative(
                    SYSTEM_PROMPT, retryPrompt, prefill, MEGA_CALL_TEMPERATURE);
        } catch (DomainException e) {
            log.warn("Mega-call retry failed: {}", e.getMessage());
            throw e;
        }

        Map<String, String> retryParsed = tryParse(retry, analyses);
        if (retryParsed == null) {
            throw new DomainException(
                    com.fxbrief.common.constants.ErrorCodes.NARRATIVE_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Mega-call returned invalid JSON twice");
        }
        return validateAndFilter(retryParsed, analyses);
    }

    /**
     * Parses Claude's mega-call response. Returns the per-pair map on
     * success, or null when the response is not valid JSON or does not
     * match the expected schema (which triggers a retry in the caller).
     * Enforces order parity: the response must list pairs in the same
     * order the prompt specified.
     */
    private Map<String, String> tryParse(String response, List<PairAnalysis> analyses) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            String cleaned = stripJsonNoise(response);
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode narrativesNode = root.path("narratives");
            if (!narrativesNode.isArray() || narrativesNode.size() != analyses.size()) {
                log.warn("Mega-call response narratives array has wrong size: expected={} got={}",
                        analyses.size(), narrativesNode.size());
                return null;
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (int i = 0; i < analyses.size(); i++) {
                JsonNode entry = narrativesNode.get(i);
                String responsePair = entry.path("pair").asText(null);
                String responseNarrative = entry.path("narrative").asText(null);
                String expectedPair = analyses.get(i).pair();
                if (!expectedPair.equals(responsePair)) {
                    log.warn("Mega-call order parity violated at index {}: expected={} got={}",
                            i, expectedPair, responsePair);
                    return null;
                }
                if (responseNarrative == null) {
                    log.warn("Mega-call entry for {} missing narrative field", expectedPair);
                    return null;
                }
                result.put(expectedPair, responseNarrative);
            }
            return result;
        } catch (Exception e) {
            log.warn("Mega-call JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Claude sometimes wraps JSON in markdown fences despite explicit
     * instructions not to. Strip ```json prefixes and trailing ``` if
     * present, then locate the first { and last } as the JSON envelope.
     */
    private String stripJsonNoise(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    /**
     * Run the validator against each parsed narrative; drop entries that
     * fail so the caller substitutes a per-pair fallback for them.
     */
    private Map<String, String> validateAndFilter(Map<String, String> parsed,
                                                  List<PairAnalysis> analyses) {
        Map<String, PairAnalysis> byPair = new LinkedHashMap<>();
        analyses.forEach(a -> byPair.put(a.pair(), a));

        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            String pair = entry.getKey();
            String narrative = entry.getValue();
            String failure = validator.validate(pair, narrative, byPair.get(pair));
            if (failure == null) {
                validated.put(pair, narrative);
            } else {
                log.warn("Mega-call narrative for {} dropped: {}", pair, failure);
            }
        }
        return validated;
    }

    /**
     * Builds the user prompt with one fenced block per pair. Each block
     * contains exactly the same structured data the per-pair generator
     * produces, so a per-pair fallback call yields a directly-comparable
     * narrative (useful for shadow-QA divergence measurement, D-046).
     */
    String buildPrompt(List<PairAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate one narrative per pair. Pairs follow, in order:\n\n");
        for (PairAnalysis a : analyses) {
            sb.append("<pair_data id=\"").append(a.pair()).append("\">\n");
            sb.append(buildPerPairBlock(a));
            sb.append("</pair_data>\n\n");
        }
        sb.append("Return JSON: {\"narratives\":[{\"pair\":\"<symbol>\",\"narrative\":\"...\"}, ...]}");
        return sb.toString();
    }

    private String buildPerPairBlock(PairAnalysis a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pair: ").append(a.pair()).append('\n');
        sb.append("Signal status: ").append(a.signalState()).append('\n');
        sb.append("Layer: ").append(a.layer()).append('\n');

        sb.append("Structure:\n");
        for (String tf : List.of("W", "D", "H4", "M15")) {
            var s = a.structureByTimeframe().get(tf);
            if (s != null) {
                sb.append("  ").append(tf).append(": bias=").append(s.bias())
                        .append(" lastEvent=").append(s.lastEvent()).append('\n');
            }
        }

        var zone = a.activeZone();
        if (zone != null) {
            sb.append("Zone: kind=").append(zone.kind())
                    .append(" range=").append(formatPrice(zone.low()))
                    .append("-").append(formatPrice(zone.high()))
                    .append(" freshness=").append(zone.freshnessState())
                    .append(" freshnessScore=").append(zone.freshnessScore())
                    .append(" maxPenetration=").append(String.format(Locale.US, "%.2f", zone.maxPenetrationRatio()))
                    .append(" penetrationDepth=").append(zone.penetrationDepth())
                    .append(" fibLevels=").append(zone.fibonacciLevelsInside())
                    .append(" invalidated=").append(zone.invalidated()).append('\n');
        } else {
            sb.append("Zone: none\n");
        }

        var m15 = a.m15Confirmation();
        if (m15 != null) {
            sb.append("M15 confirmation: confirmed=").append(m15.confirmed())
                    .append(" sweep=").append(m15.liquiditySweep())
                    .append(" bos=").append(m15.m15Bos())
                    .append(" engulfing=").append(m15.engulfingDisplacement())
                    .append(" choch=").append(m15.chochAlignedWithHtf()).append('\n');
        }

        var c = a.confidence();
        if (c != null) {
            sb.append("Confidence: score=").append(c.score()).append(" level=").append(c.level()).append('\n');
            if (c.factors() != null) {
                c.factors().forEach(f -> sb.append("  - ").append(f.label())
                        .append(": ").append(f.applied() ? "applied" : "not applied")
                        .append(" (").append(f.delta() >= 0 ? "+" : "").append(f.delta()).append(")\n"));
            }
        }

        var trade = a.tradePlan();
        if (trade != null) {
            sb.append("Trade plan: direction=").append(trade.direction())
                    .append(" entry=").append(formatPrice(trade.entryLow()))
                    .append("-").append(formatPrice(trade.entryHigh()))
                    .append(" tp=").append(formatPrice(trade.takeProfit()))
                    .append(" sl=").append(formatPrice(trade.stopLoss())).append('\n');
        }

        var f = a.fundamental();
        if (f != null) {
            sb.append("Fundamental: currency=").append(f.currency())
                    .append(" bias=").append(f.bias())
                    .append(" highImpactThisWeek=").append(f.highImpactThisWeek()).append('\n');
            if (f.events() != null && !f.events().isEmpty()) {
                int max = Math.min(5, f.events().size());
                for (int i = 0; i < max; i++) {
                    var e = f.events().get(i);
                    sb.append("  event: ").append(e.event())
                            .append(" importance=").append(e.importance());
                    if (e.actual() != null || e.forecast() != null) {
                        sb.append(" actual=").append(e.actual()).append(" forecast=").append(e.forecast());
                    }
                    sb.append('\n');
                }
            }
        }
        if (a.htfConflict()) {
            sb.append("Note: weekly and daily bias conflict.\n");
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
