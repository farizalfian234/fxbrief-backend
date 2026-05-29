package com.fxbrief.report.service;

import com.fxbrief.analysis.dto.ConfidenceScoreView;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.ReportPayload;
import com.fxbrief.analysis.dto.SignalState;
import com.fxbrief.analysis.dto.Zone;
import com.fxbrief.report.dto.PreferenceSnapshot;
import com.fxbrief.report.entity.PreferenceType;
import com.fxbrief.report.entity.PreferenceValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes the per-pair {@code finalDisplayScore} for a report when the
 * generating user has an active preference (override or persisted).
 *
 * <p>Per Addition 3:
 *
 * <pre>
 * finalDisplayScore = (marketScore × 0.8) + (userCompatibilityScore × 0.2)
 * </pre>
 *
 * where {@code marketScore = confidence.score} (already produced by the
 * analysis engine) and {@code userCompatibilityScore} is in {@code [0.0, 1.0]}.
 * The 0.8/0.2 weighting ensures market signal dominates — preferences are a
 * tiebreaker, never an override (DECISIONS D-056).
 *
 * <p>This service is stateless and free of I/O. Output is a deterministic
 * function of inputs; the same payload + snapshot always yields the same
 * scores, which is what makes reordering on every read of a stored report
 * safe and consistent.
 *
 * <h2>Per-type rules</h2>
 *
 * The spec lists the input signals for each preference type but not exact
 * weights. The weights below were chosen to (a) keep
 * {@code userCompatibilityScore} in {@code [0.0, 1.0]} and (b) reward each
 * signal proportionally to its emphasis in the spec text. They are
 * intentionally simple integer fractions so future tuning is obvious. Any
 * change here only affects future generations — existing stored
 * {@code final_display_scores} are frozen at write time and never recomputed.
 */
@Slf4j
@Component
public class PreferenceScorer {

    private static final double MARKET_WEIGHT = 0.8;
    private static final double USER_WEIGHT = 0.2;

    /**
     * Computes the final display score for every pair in the payload.
     *
     * @return ordered map of pair symbol → finalDisplayScore. Order
     *         matches the input pair order; callers reorder as needed.
     */
    public Map<String, Double> score(ReportPayload payload, PreferenceSnapshot snapshot) {
        if (payload == null || payload.pairs() == null || snapshot == null) {
            return Map.of();
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (PairAnalysis pair : payload.pairs()) {
            double marketScore = marketScoreOf(pair);
            double userScore = userCompatibilityScore(pair, snapshot);
            double finalScore = (marketScore * MARKET_WEIGHT) + (userScore * USER_WEIGHT);
            result.put(pair.pair(), finalScore);
        }
        return result;
    }

    private double marketScoreOf(PairAnalysis pair) {
        ConfidenceScoreView confidence = pair.confidence();
        return confidence == null ? 0.0 : confidence.score();
    }

    /**
     * Dispatches to the right scoring strategy. Always returns a value in
     * {@code [0.0, 1.0]}.
     */
    double userCompatibilityScore(PairAnalysis pair, PreferenceSnapshot snapshot) {
        PreferenceType type = snapshot.preferenceType();
        String value = snapshot.preferenceValue();

        try {
            return switch (type) {
                case TRADING_STYLE -> scoreTradingStyle(pair, value);
                case PREFERRED_SESSION -> scorePreferredSession(pair, value);
                case RISK_PROFILE -> scoreRiskProfile(pair, value);
                case FAVORITE_PAIR -> scoreFavoritePair(pair, value);
            };
        } catch (Exception e) {
            // Defensive: never let scoring blow up the report. A bad snapshot
            // (e.g. invalid enum string round-tripped from old data) degrades
            // to neutral 0.5 so the report still renders.
            log.warn("Preference scoring failed for pair={} type={} value={}: {}",
                    pair.pair(), type, value, e.getMessage());
            return 0.5;
        }
    }

    // ============================================================
    // TRADING_STYLE
    // ============================================================
    //
    // SCALPER: m15Confirmation.confirmed (high weight) +
    //          signalState = CONFIRMED +
    //          penetrationDepth not DEEP
    //
    // INTRADAY: H4 + M15 alignment +
    //           signalState in {CONFIRMED, AWAITING_CONFIRMATION}
    //
    // SWING_TRADER: W + D HTF alignment +
    //               htfConflict = false +
    //               setup maturity (CONFIRMED or AWAITING)
    //
    // POSITION_TRADER: W bias strength +
    //                  continuation potential (no INVALIDATED/EXPIRED)
    //
    private double scoreTradingStyle(PairAnalysis pair, String value) {
        PreferenceValue style = PreferenceValue.resolve(PreferenceType.TRADING_STYLE, value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Not a TRADING_STYLE value: " + value));

        return switch (style) {
            case SCALPER -> scoreScalper(pair);
            case INTRADAY -> scoreIntraday(pair);
            case SWING_TRADER -> scoreSwingTrader(pair);
            case POSITION_TRADER -> scorePositionTrader(pair);
            default -> throw new IllegalStateException("Unexpected: " + style);
        };
    }

    /**
     * SCALPER weights:
     *   M15 confirmation = 0.5 (high weight per spec)
     *   signalState == CONFIRMED = 0.3
     *   penetrationDepth != DEEP = 0.2
     * Total max = 1.0
     */
    private double scoreScalper(PairAnalysis pair) {
        double score = 0.0;
        if (pair.m15Confirmation() != null && pair.m15Confirmation().confirmed()) {
            score += 0.5;
        }
        if (pair.signalState() == SignalState.CONFIRMED) {
            score += 0.3;
        }
        if (pair.activeZone() == null
                || pair.activeZone().penetrationDepth() != Zone.PenetrationDepth.DEEP) {
            score += 0.2;
        }
        return score;
    }

    /**
     * INTRADAY weights:
     *   H4+M15 aligned (both non-RANGING, same direction) = 0.5
     *   signalState in {CONFIRMED, AWAITING_CONFIRMATION} = 0.5
     * Total max = 1.0
     */
    private double scoreIntraday(PairAnalysis pair) {
        double score = 0.0;
        if (h4M15Aligned(pair)) {
            score += 0.5;
        }
        SignalState state = pair.signalState();
        if (state == SignalState.CONFIRMED || state == SignalState.AWAITING_CONFIRMATION) {
            score += 0.5;
        }
        return score;
    }

    /**
     * SWING_TRADER weights:
     *   W+D aligned (both non-RANGING, same direction) = 0.4
     *   htfConflict == false = 0.2
     *   signalState in {CONFIRMED, AWAITING_CONFIRMATION} = 0.4
     * Total max = 1.0
     */
    private double scoreSwingTrader(PairAnalysis pair) {
        double score = 0.0;
        if (weeklyDailyAligned(pair)) {
            score += 0.4;
        }
        if (!pair.htfConflict()) {
            score += 0.2;
        }
        SignalState state = pair.signalState();
        if (state == SignalState.CONFIRMED || state == SignalState.AWAITING_CONFIRMATION) {
            score += 0.4;
        }
        return score;
    }

    /**
     * POSITION_TRADER weights:
     *   Weekly bias non-RANGING = 0.6 (W bias strength)
     *   signalState not in {INVALIDATED, EXPIRED} = 0.4 (continuation potential)
     * Total max = 1.0
     */
    private double scorePositionTrader(PairAnalysis pair) {
        double score = 0.0;
        MarketStructureView weekly = structureFor(pair, "W");
        if (weekly != null && weekly.bias() != MarketStructureView.MarketBias.RANGING) {
            score += 0.6;
        }
        SignalState state = pair.signalState();
        if (state != SignalState.INVALIDATED && state != SignalState.EXPIRED) {
            score += 0.4;
        }
        return score;
    }

    // ============================================================
    // PREFERRED_SESSION
    // ============================================================
    private double scorePreferredSession(PairAnalysis pair, String value) {
        PreferenceValue session = PreferenceValue.resolve(PreferenceType.PREFERRED_SESSION, value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Not a PREFERRED_SESSION value: " + value));
        return SessionAffinity.score(session, pair.pair());
    }

    // ============================================================
    // RISK_PROFILE
    // ============================================================
    //
    // CONSERVATIVE: confidence.level == HIGH AND
    //               m15Confirmation.confirmed == true AND
    //               fundamentalConflict == false AND
    //               htfConflict == false  → 1.0, otherwise 0.0
    // BALANCED:     confidence.level in {MEDIUM, HIGH} AND
    //               signalState in {CONFIRMED, AWAITING_CONFIRMATION} → 1.0, else 0.0
    // AGGRESSIVE:   signalState not in {EXPIRED, INVALIDATED} → 1.0, else 0.0
    //
    // The spec says CONSERVATIVE/BALANCED are "high score for [conditions]" and
    // AGGRESSIVE "scores equally" — i.e. the gates are pass/fail. Binary 1.0/0.0
    // matches that wording and keeps the userCompatibilityScore in [0,1].
    //
    private double scoreRiskProfile(PairAnalysis pair, String value) {
        PreferenceValue profile = PreferenceValue.resolve(PreferenceType.RISK_PROFILE, value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Not a RISK_PROFILE value: " + value));

        return switch (profile) {
            case CONSERVATIVE -> scoreConservative(pair) ? 1.0 : 0.0;
            case BALANCED -> scoreBalanced(pair) ? 1.0 : 0.0;
            case AGGRESSIVE -> scoreAggressive(pair) ? 1.0 : 0.0;
            default -> throw new IllegalStateException("Unexpected: " + profile);
        };
    }

    private boolean scoreConservative(PairAnalysis pair) {
        ConfidenceScoreView confidence = pair.confidence();
        boolean highConfidence = confidence != null
                && confidence.level() == ConfidenceScoreView.ConfidenceLevel.HIGH;
        boolean m15Confirmed = pair.m15Confirmation() != null
                && pair.m15Confirmation().confirmed();
        return highConfidence
                && m15Confirmed
                && !pair.fundamentalConflict()
                && !pair.htfConflict();
    }

    private boolean scoreBalanced(PairAnalysis pair) {
        ConfidenceScoreView confidence = pair.confidence();
        boolean mediumOrHigh = confidence != null
                && (confidence.level() == ConfidenceScoreView.ConfidenceLevel.MEDIUM
                    || confidence.level() == ConfidenceScoreView.ConfidenceLevel.HIGH);
        SignalState state = pair.signalState();
        boolean activeState = state == SignalState.CONFIRMED
                || state == SignalState.AWAITING_CONFIRMATION;
        return mediumOrHigh && activeState;
    }

    private boolean scoreAggressive(PairAnalysis pair) {
        SignalState state = pair.signalState();
        return state != SignalState.EXPIRED && state != SignalState.INVALIDATED;
    }

    // ============================================================
    // FAVORITE_PAIR
    // ============================================================
    //
    // value is a pair symbol (e.g. "EUR/USD"). Validated by the resolver
    // before reaching this scorer; here we just compare.
    //
    private double scoreFavoritePair(PairAnalysis pair, String favoriteSymbol) {
        if (favoriteSymbol == null) {
            return 0.5;
        }
        return favoriteSymbol.equals(pair.pair()) ? 1.0 : 0.5;
    }

    // ============================================================
    // Helpers
    // ============================================================

    private MarketStructureView structureFor(PairAnalysis pair, String timeframeCode) {
        if (pair.structureByTimeframe() == null) {
            return null;
        }
        return pair.structureByTimeframe().get(timeframeCode);
    }

    /**
     * Two timeframes are "aligned" when both are non-RANGING and share the
     * same bias.
     */
    private boolean h4M15Aligned(PairAnalysis pair) {
        return aligned(structureFor(pair, "H4"), structureFor(pair, "M15"));
    }

    private boolean weeklyDailyAligned(PairAnalysis pair) {
        return aligned(structureFor(pair, "W"), structureFor(pair, "D"));
    }

    private boolean aligned(MarketStructureView a, MarketStructureView b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.bias() == MarketStructureView.MarketBias.RANGING
                || b.bias() == MarketStructureView.MarketBias.RANGING) {
            return false;
        }
        return a.bias() == b.bias();
    }
}
