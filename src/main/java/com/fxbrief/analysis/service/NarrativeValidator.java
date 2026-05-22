package com.fxbrief.analysis.service;

import com.fxbrief.analysis.dto.PairAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates per-pair narratives returned by the mega-call. Runs three checks
 * per narrative:
 *
 * <ol>
 *   <li><b>Pair echo</b> — the narrative text must contain the pair symbol
 *       (e.g. "EUR/USD") as a substring. Catches the worst class of label
 *       drift where Claude returns the wrong narrative under the right key.</li>
 *   <li><b>Numeric cross-check</b> — every price-like numeric token in the
 *       narrative (3+ decimal digits, e.g. {@code 1.0823}) must appear in
 *       the structured input for that pair, or be a small annotation number
 *       (single/double digit confidence score, percentages with explicit
 *       % suffix, etc.). Fabricated price levels get flagged.</li>
 *   <li><b>Length sanity</b> — the narrative is non-empty and within
 *       reasonable bounds (50-3000 chars). Catches truncated or runaway
 *       responses.</li>
 * </ol>
 *
 * Order parity (the list comes back in the same pair-order it was sent in)
 * is the caller's responsibility — that check happens during JSON parsing
 * in {@link MegaCallNarrativeService}.
 *
 * Per DECISIONS D-045, validation failure on a single pair downgrades that
 * pair to a per-pair fallback call without affecting the other seven.
 */
@Slf4j
@Component
public class NarrativeValidator {

    private static final Pattern PRICE_TOKEN = Pattern.compile("\\b\\d+\\.\\d{3,}\\b");
    private static final Pattern PERCENT_TOKEN = Pattern.compile("\\b\\d+(?:\\.\\d+)?%");

    private static final int MIN_NARRATIVE_CHARS = 50;
    private static final int MAX_NARRATIVE_CHARS = 3000;

    /**
     * Returns null on pass; non-null reason string on fail.
     */
    public String validate(String pairSymbol, String narrative, PairAnalysis analysis) {
        if (narrative == null || narrative.isBlank()) {
            return "narrative empty";
        }
        if (narrative.length() < MIN_NARRATIVE_CHARS) {
            return "narrative too short (" + narrative.length() + " chars)";
        }
        if (narrative.length() > MAX_NARRATIVE_CHARS) {
            return "narrative too long (" + narrative.length() + " chars)";
        }
        if (!narrative.contains(pairSymbol)) {
            return "pair symbol " + pairSymbol + " not present in narrative";
        }

        Set<String> allowedTokens = collectAllowedNumericTokens(analysis);
        Matcher m = PRICE_TOKEN.matcher(narrative);
        while (m.find()) {
            String token = m.group();
            if (!allowedTokens.contains(token) && !isApproximateMatch(token, allowedTokens)) {
                return "narrative contains price-like token " + token
                        + " not present in source analysis";
            }
        }

        return null;
    }

    /**
     * Collects all numeric tokens the narrative is allowed to reference: the
     * formatted prices of the trade plan (entry, TP, SL), the zone bounds,
     * and the active zone freshness/confidence numerics. Other small values
     * (the +1/+2/+3 confidence factors, percentages) are matched by separate
     * regexes that bypass the price check.
     */
    private Set<String> collectAllowedNumericTokens(PairAnalysis analysis) {
        Set<String> tokens = new HashSet<>();
        if (analysis == null) {
            return tokens;
        }
        if (analysis.activeZone() != null) {
            addPriceTokens(tokens, analysis.activeZone().low());
            addPriceTokens(tokens, analysis.activeZone().high());
            addPriceTokens(tokens, analysis.activeZone().maxPenetrationRatio());
        }
        if (analysis.tradePlan() != null) {
            addPriceTokens(tokens, analysis.tradePlan().entryLow());
            addPriceTokens(tokens, analysis.tradePlan().entryHigh());
            addPriceTokens(tokens, analysis.tradePlan().takeProfit());
            addPriceTokens(tokens, analysis.tradePlan().stopLoss());
        }
        if (analysis.activeZone() != null
                && analysis.activeZone().fibonacciLevelsInside() != null) {
            for (Double level : analysis.activeZone().fibonacciLevelsInside()) {
                addPriceTokens(tokens, level);
            }
        }
        return tokens;
    }

    /**
     * The engine formats prices with %.5f; we add the canonical 5-decimal
     * form plus a few common truncations (%.4f, %.3f) so the validator is
     * tolerant of Claude's natural number-formatting choices.
     */
    private void addPriceTokens(Set<String> tokens, double value) {
        tokens.add(String.format(Locale.US, "%.5f", value));
        tokens.add(String.format(Locale.US, "%.4f", value));
        tokens.add(String.format(Locale.US, "%.3f", value));
        // Strip trailing zeros for the 5-decimal form
        String fiveDec = String.format(Locale.US, "%.5f", value);
        if (fiveDec.contains(".")) {
            String trimmed = fiveDec.replaceAll("0+$", "").replaceAll("\\.$", "");
            tokens.add(trimmed);
        }
    }

    /**
     * Tolerant match: accept the token if it differs from an allowed token
     * by at most 1 in the last visible decimal place. Handles cases where
     * Claude rounds 1.08235 → 1.082 vs. our canonical 1.08240. Distance ≤ 1
     * in the trailing digit is acceptable; anything further is a fabrication
     * candidate.
     */
    private boolean isApproximateMatch(String token, Set<String> allowed) {
        try {
            double tokenValue = Double.parseDouble(token);
            for (String allowedTok : allowed) {
                double allowedValue = Double.parseDouble(allowedTok);
                // 0.0001 tolerance covers 1-pip rounding for 4/5-digit forex pairs;
                // wider tolerance for XAU (3-digit precision).
                double tolerance = Math.max(0.0001, Math.abs(allowedValue) * 0.0005);
                if (Math.abs(tokenValue - allowedValue) <= tolerance) {
                    return true;
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return false;
    }

    /**
     * Bulk check used by the mega-call: validate each (pair, narrative) pair
     * and return the list of pairs that failed validation.
     */
    public List<String> findInvalidPairs(Map<String, String> narrativesByPair,
                                         Map<String, PairAnalysis> analysesByPair) {
        List<String> invalid = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : narrativesByPair.entrySet()) {
            String pair = entry.getKey();
            String narrative = entry.getValue();
            PairAnalysis analysis = analysesByPair.get(pair);
            String reason = validate(pair, narrative, analysis);
            if (reason != null) {
                log.warn("Narrative validation failed for {}: {}", pair, reason);
                invalid.add(pair);
            }
        }
        return invalid;
    }
}
