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
 * report-level summary string. This validator runs five classes of check:
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
 *   <li><b>Forbidden price tokens (D-089)</b> — {@code shortReasoning},
 *       {@code executiveReasoning}, and {@code fundamentalSummary} must
 *       contain <em>no</em> price-like tokens at all, even real ones from
 *       the structured input. Those three fields are pure prose; price
 *       levels belong in {@code invalidationNote} and the structured data.</li>
 *   <li><b>Sentence count (D-089)</b> — {@code shortReasoning} must be
 *       exactly 1 sentence; {@code executiveReasoning} exactly 3;
 *       {@code fundamentalSummary} exactly 2. Out-of-band counts are
 *       hard rejects that downgrade the pair to per-pair fallback.</li>
 *   <li><b>Length bands</b> — each field is non-blank and within a
 *       per-field length band (PRD §8.4 + D-089). For the three tightened
 *       fields the max is a hard cap; for {@code setupStatus} and
 *       {@code invalidationNote} the max remains a soft warning only.</li>
 * </ol>
 *
 * Per DECISIONS D-045, validation failure on a single pair downgrades that
 * pair to a per-pair fallback call without affecting the other seven.
 * "Validation failure" here means a hard reject. The per-pair fallback path
 * uses the same tightened prompt (D-089), so a failure is most likely
 * Claude drifting on length or sentence count rather than fabricating
 * content — but the existing fallback contract is unchanged.
 *
 * Order parity (the list comes back in the same pair-order it was sent in)
 * is the caller's responsibility — that check happens during JSON parsing
 * in {@link MegaCallNarrativeService}.
 */
@Slf4j
@Component
public class NarrativeValidator {

    private static final Pattern PRICE_TOKEN = Pattern.compile("\\b\\d+\\.\\d{3,}\\b");

    /**
     * Sentence splitter: splits on one or more sentence-ending punctuation
     * marks ({@code .}, {@code !}, {@code ?}) followed by whitespace or end
     * of input.
     *
     * <p>The {@code (?<![A-Z]\.[A-Z])} lookbehind suppresses the split when
     * the period is part of a single-letter abbreviation like "U.S." — the
     * terminal period of such an abbreviation is preceded by the pattern
     * {@code [A-Z]\.[A-Z]} and gets ignored. This handles the in-the-middle-
     * of-a-sentence case where the abbreviation's terminal period is
     * followed by a space and would otherwise look like a sentence break.
     *
     * <p>The result is further filtered to drop fragments shorter than
     * {@link #MIN_SENTENCE_CHARS} as a backstop for any abbreviation
     * pattern the lookbehind doesn't cover.
     */
    private static final Pattern SENTENCE_SPLITTER =
            Pattern.compile("(?<![A-Z]\\.[A-Z])[.!?]+(?:\\s+|$)");
    private static final int MIN_SENTENCE_CHARS = 4;

    // -----------------------------------------------------------------
    // Per-field length bands.
    //
    // For setupStatus and invalidationNote: min is a hard floor, max is a
    // soft warning only (unchanged from D-051).
    //
    // For shortReasoning, executiveReasoning, fundamentalSummary (D-089):
    // both min AND max are hard. The min relaxed to accommodate the new
    // tighter targets; the max is the new constraint that produced the
    // tightening request.
    // -----------------------------------------------------------------
    private static final int SETUP_STATUS_MIN = 10;
    private static final int SETUP_STATUS_SOFT_MAX = 200;

    private static final int SHORT_REASONING_MIN = 20;
    private static final int SHORT_REASONING_HARD_MAX = 120;
    private static final int SHORT_REASONING_EXPECTED_SENTENCES = 1;

    private static final int EXECUTIVE_REASONING_MIN = 80;
    private static final int EXECUTIVE_REASONING_HARD_MAX = 280;
    private static final int EXECUTIVE_REASONING_EXPECTED_SENTENCES = 3;

    private static final int INVALIDATION_NOTE_MIN = 20;
    private static final int INVALIDATION_NOTE_SOFT_MAX = 250;

    private static final int FUNDAMENTAL_SUMMARY_MIN = 30;
    private static final int FUNDAMENTAL_SUMMARY_HARD_MAX = 200;
    private static final int FUNDAMENTAL_SUMMARY_EXPECTED_SENTENCES = 2;

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

        // setupStatus and invalidationNote: existing rules — hard min + soft max.
        String hard = hardCheckField("setupStatus", fields.setupStatus(), SETUP_STATUS_MIN);
        if (hard != null) return hard;
        hard = hardCheckField("invalidationNote", fields.invalidationNote(), INVALIDATION_NOTE_MIN);
        if (hard != null) return hard;
        softCheckMax("setupStatus", pairSymbol, fields.setupStatus(), SETUP_STATUS_SOFT_MAX);
        softCheckMax("invalidationNote", pairSymbol, fields.invalidationNote(), INVALIDATION_NOTE_SOFT_MAX);

        // shortReasoning: hard min, hard max (120), exact 1 sentence, no price tokens.
        hard = strictCheckField("shortReasoning", fields.shortReasoning(),
                SHORT_REASONING_MIN, SHORT_REASONING_HARD_MAX,
                SHORT_REASONING_EXPECTED_SENTENCES);
        if (hard != null) return hard;
        hard = forbidPriceTokens("shortReasoning", fields.shortReasoning());
        if (hard != null) return hard;

        // executiveReasoning: hard min, hard max (280), exact 3 sentences, no price tokens.
        hard = strictCheckField("executiveReasoning", fields.executiveReasoning(),
                EXECUTIVE_REASONING_MIN, EXECUTIVE_REASONING_HARD_MAX,
                EXECUTIVE_REASONING_EXPECTED_SENTENCES);
        if (hard != null) return hard;
        hard = forbidPriceTokens("executiveReasoning", fields.executiveReasoning());
        if (hard != null) return hard;

        // fundamentalSummary: hard min, hard max (200), exact 2 sentences, no price tokens.
        hard = strictCheckField("fundamentalSummary", fields.fundamentalSummary(),
                FUNDAMENTAL_SUMMARY_MIN, FUNDAMENTAL_SUMMARY_HARD_MAX,
                FUNDAMENTAL_SUMMARY_EXPECTED_SENTENCES);
        if (hard != null) return hard;
        hard = forbidPriceTokens("fundamentalSummary", fields.fundamentalSummary());
        if (hard != null) return hard;

        // Pair echo: at least one of the five fields must contain the pair symbol.
        // The card sections are short and may legitimately omit the symbol; the
        // accordion fields normally include it. So the union must contain it.
        String all = (fields.setupStatus() + " " + fields.shortReasoning() + " "
                + fields.executiveReasoning() + " " + fields.invalidationNote() + " "
                + fields.fundamentalSummary());
        if (!all.contains(pairSymbol)) {
            return "pair symbol " + pairSymbol + " not present in any of the five fields";
        }

        // Numeric cross-check across all five fields. Note that the
        // forbid-price-tokens check above already rejected any price-like
        // tokens in the three tightened fields; this remaining check
        // primarily polices invalidationNote (which is allowed to contain
        // prices) for fabricated values.
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

    /**
     * Strict per-field check for tightened fields (D-089): hard min, hard
     * max, and exact sentence count. Returns null on pass, non-null reason
     * on fail.
     */
    private String strictCheckField(String fieldName, String value,
                                    int minChars, int hardMaxChars, int expectedSentences) {
        if (value == null || value.isBlank()) {
            return fieldName + " is empty";
        }
        if (value.length() < minChars) {
            return fieldName + " too short (" + value.length() + " chars, min " + minChars + ")";
        }
        if (value.length() > hardMaxChars) {
            return fieldName + " too long (" + value.length() + " chars, max " + hardMaxChars + ")";
        }
        int sentences = countSentences(value);
        if (sentences != expectedSentences) {
            return fieldName + " sentence count " + sentences
                    + " (expected exactly " + expectedSentences + ")";
        }
        return null;
    }

    /**
     * Rejects any price-like token in the given field. Used for the three
     * tightened fields (D-089) which must be pure prose with no price
     * levels, zone numbers, percentages, or R:R figures embedded.
     * {@code invalidationNote} is exempt — its purpose is to state a
     * price-based condition.
     *
     * Returns null on pass, a "contains price token" reason on fail.
     */
    private String forbidPriceTokens(String fieldName, String value) {
        if (value == null) {
            return null;
        }
        Matcher m = PRICE_TOKEN.matcher(value);
        if (m.find()) {
            return fieldName + " contains a price-like token (" + m.group()
                    + ") — pure prose only, no numeric levels";
        }
        return null;
    }

    /**
     * Counts sentences in a free-text string. Splits on
     * {@code [.!?]+} followed by whitespace or end-of-string. A lookbehind
     * suppresses splits inside single-letter abbreviations like "U.S.";
     * remaining short fragments (shorter than {@link #MIN_SENTENCE_CHARS})
     * are dropped as a backstop.
     *
     * The trailing punctuation that ends the last sentence may or may not
     * be present; either case counts as a single trailing sentence as long
     * as the fragment is non-trivially long.
     */
    int countSentences(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String[] parts = SENTENCE_SPLITTER.split(text.trim());
        int count = 0;
        for (String part : parts) {
            if (part.trim().length() >= MIN_SENTENCE_CHARS) {
                count++;
            }
        }
        return count;
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
