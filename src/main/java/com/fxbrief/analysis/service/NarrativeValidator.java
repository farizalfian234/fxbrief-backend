package com.fxbrief.analysis.service;

import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.PairNarrativeFields;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates Claude-generated content returned by the mega-call.
 *
 * Per PRD §8.4 Claude produces five named text fields per pair plus one
 * report-level summary string. This validator runs three classes of check:
 *
 * <ol>
 *   <li><b>Pair echo</b> — at least one of the per-pair fields must contain
 *       the pair symbol (e.g. {@code "EUR/USD"}). Catches the worst class of
 *       label drift where Claude returns content for the wrong pair under
 *       the right key.</li>
 *   <li><b>Numeric cross-check</b> — every price-like token (3+ decimal
 *       digits, e.g. {@code 1.0823}) anywhere across the five fields must
 *       appear in the structured input for that pair, or be approximately
 *       close to a known value. Fabricated price levels get flagged.</li>
 *   <li><b>Length sanity</b> — each field is non-blank and within a
 *       reasonable per-field length band (PRD §8.4). Out-of-band lengths
 *       are <b>logged as warnings only</b> — the field is accepted as long
 *       as it is non-blank. Hard rejection is reserved for actual schema
 *       and numeric problems, not stylistic ones (decision: chosen by the
 *       project owner — see DECISIONS D-051).</li>
 * </ol>
 *
 * Per DECISIONS D-045, validation failure on a single pair downgrades that
 * pair to a per-pair fallback call without affecting the other seven.
 * "Validation failure" here means a hard reject — empty content, missing
 * pair echo, or a fabricated price token.
 *
 * Order parity (the list comes back in the same pair-order it was sent in)
 * is the caller's responsibility — that check happens during JSON parsing
 * in {@link MegaCallNarrativeService}.
 */
@Slf4j
@Component
public class NarrativeValidator {

    private static final Pattern PRICE_TOKEN = Pattern.compile("\\b\\d+\\.\\d{3,}\\b");

    // Per-field length bands. The lower bound is a hard floor (below this is
    // a hard reject — the field is effectively missing). The upper bound is
    // a soft warning only.
    private static final int SETUP_STATUS_MIN = 10;
    private static final int SETUP_STATUS_SOFT_MAX = 200;
    private static final int SHORT_REASONING_MIN = 30;
    private static final int SHORT_REASONING_SOFT_MAX = 400;
    private static final int EXECUTIVE_REASONING_MIN = 80;
    private static final int EXECUTIVE_REASONING_SOFT_MAX = 1200;
    private static final int INVALIDATION_NOTE_MIN = 20;
    private static final int INVALIDATION_NOTE_SOFT_MAX = 250;
    private static final int FUNDAMENTAL_SUMMARY_MIN = 30;
    private static final int FUNDAMENTAL_SUMMARY_SOFT_MAX = 600;

    public static final int SUMMARY_MAX_CHARS = 100;

    /**
     * Validates the five per-pair fields. Returns {@code null} on pass;
     * non-null reason string on hard fail.
     */
    public String validatePair(String pairSymbol, PairNarrativeFields fields,
                               PairAnalysis analysis) {
        if (fields == null) {
            return "narrative fields object is null";
        }

        String hard = hardCheckField("setupStatus", fields.setupStatus(), SETUP_STATUS_MIN);
        if (hard != null) return hard;
        hard = hardCheckField("shortReasoning", fields.shortReasoning(), SHORT_REASONING_MIN);
        if (hard != null) return hard;
        hard = hardCheckField("executiveReasoning", fields.executiveReasoning(), EXECUTIVE_REASONING_MIN);
        if (hard != null) return hard;
        hard = hardCheckField("invalidationNote", fields.invalidationNote(), INVALIDATION_NOTE_MIN);
        if (hard != null) return hard;
        hard = hardCheckField("fundamentalSummary", fields.fundamentalSummary(), FUNDAMENTAL_SUMMARY_MIN);
        if (hard != null) return hard;

        softCheckMax("setupStatus", pairSymbol, fields.setupStatus(), SETUP_STATUS_SOFT_MAX);
        softCheckMax("shortReasoning", pairSymbol, fields.shortReasoning(), SHORT_REASONING_SOFT_MAX);
        softCheckMax("executiveReasoning", pairSymbol, fields.executiveReasoning(), EXECUTIVE_REASONING_SOFT_MAX);
        softCheckMax("invalidationNote", pairSymbol, fields.invalidationNote(), INVALIDATION_NOTE_SOFT_MAX);
        softCheckMax("fundamentalSummary", pairSymbol, fields.fundamentalSummary(), FUNDAMENTAL_SUMMARY_SOFT_MAX);

        // Pair echo: at least one of the five fields must contain the pair symbol.
        // The card sections are short and may legitimately omit the symbol; the
        // accordion fields normally include it. So the union must contain it.
        String all = (fields.setupStatus() + " " + fields.shortReasoning() + " "
                + fields.executiveReasoning() + " " + fields.invalidationNote() + " "
                + fields.fundamentalSummary());
        if (!all.contains(pairSymbol)) {
            return "pair symbol " + pairSymbol + " not present in any of the five fields";
        }

        // Numeric cross-check across all five fields.
        Set<String> allowedTokens = collectAllowedNumericTokens(analysis);
        String fabricated = findFabricatedToken(all, allowedTokens);
        if (fabricated != null) {
            return "fabricated price-like token " + fabricated
                    + " not present in source analysis";
        }

        return null;
    }

    /**
     * Bulk check used by the mega-call: validate each pair's fields and
     * return the list of pairs that failed validation.
     */
    public List<String> findInvalidPairs(Map<String, PairNarrativeFields> fieldsByPair,
                                         Map<String, PairAnalysis> analysesByPair) {
        List<String> invalid = new ArrayList<>();
        for (Map.Entry<String, PairNarrativeFields> entry : fieldsByPair.entrySet()) {
            String pair = entry.getKey();
            PairAnalysis analysis = analysesByPair.get(pair);
            String reason = validatePair(pair, entry.getValue(), analysis);
            if (reason != null) {
                log.warn("Narrative validation failed for {}: {}", pair, reason);
                invalid.add(pair);
            }
        }
        return invalid;
    }

    /**
     * Validates the report-level summary string. Returns null on pass,
     * non-null reason on fail. Failure triggers the Java composer fallback
     * (DECISIONS D-051).
     */
    public String validateSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "summary is empty";
        }
        if (summary.length() > SUMMARY_MAX_CHARS) {
            return "summary exceeds " + SUMMARY_MAX_CHARS + " chars (" + summary.length() + ")";
        }
        return null;
    }

    private String hardCheckField(String fieldName, String value, int minChars) {
        if (value == null || value.isBlank()) {
            return fieldName + " is empty";
        }
        if (value.length() < minChars) {
            return fieldName + " too short (" + value.length() + " chars, min " + minChars + ")";
        }
        return null;
    }

    private void softCheckMax(String fieldName, String pair, String value, int softMax) {
        if (value != null && value.length() > softMax) {
            log.info("Soft-warn: {} for {} is longer than expected ({} chars > {})",
                    fieldName, pair, value.length(), softMax);
        }
    }

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

    private void addPriceTokens(Set<String> tokens, double value) {
        tokens.add(String.format(Locale.US, "%.5f", value));
        tokens.add(String.format(Locale.US, "%.4f", value));
        tokens.add(String.format(Locale.US, "%.3f", value));
        String fiveDec = String.format(Locale.US, "%.5f", value);
        if (fiveDec.contains(".")) {
            String trimmed = fiveDec.replaceAll("0+$", "").replaceAll("\\.$", "");
            tokens.add(trimmed);
        }
    }

    private String findFabricatedToken(String text, Set<String> allowed) {
        Matcher m = PRICE_TOKEN.matcher(text);
        while (m.find()) {
            String token = m.group();
            if (!allowed.contains(token) && !isApproximateMatch(token, allowed)) {
                return token;
            }
        }
        return null;
    }

    private boolean isApproximateMatch(String token, Set<String> allowed) {
        try {
            double tokenValue = Double.parseDouble(token);
            for (String allowedTok : allowed) {
                double allowedValue = Double.parseDouble(allowedTok);
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
}
