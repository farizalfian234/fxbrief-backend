package com.fxbrief.analysis.service;

import com.fxbrief.analysis.dto.ConfidenceScoreView;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.SignalState;
import com.fxbrief.analysis.entity.Timeframe;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Best-pair selection per PRD §7.11. Ranked tiers (first match wins):
 * <ol>
 *   <li>Confirmed setup with high confidence (clean technical alignment)</li>
 *   <li>Awaiting Confirmation with high confidence</li>
 *   <li>Strongly trending pair on W and D (no setup yet)</li>
 *   <li>Conflicting setup with valid zone (still tradable with caution)</li>
 *   <li>Strongest fundamental signal if no technical signal anywhere</li>
 * </ol>
 *
 * Tiebreaker within a tier: higher confidence score, then most recent
 * structure event. Returns empty when all markets are consolidating with
 * no technical or fundamental signal — caller surfaces that as the
 * "no report consumed" path per PRD §10.2.
 */
@Service
public class BestPairSelector {

    public Optional<String> select(List<PairAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return Optional.empty();
        }

        Optional<PairAnalysis> tier1 = pickHighest(analyses,
                a -> a.signalState() == SignalState.CONFIRMED
                        && isHighConfidence(a.confidence()));
        if (tier1.isPresent()) {
            return tier1.map(PairAnalysis::pair);
        }

        Optional<PairAnalysis> tier2 = pickHighest(analyses,
                a -> a.signalState() == SignalState.AWAITING_CONFIRMATION
                        && isHighConfidence(a.confidence()));
        if (tier2.isPresent()) {
            return tier2.map(PairAnalysis::pair);
        }

        Optional<PairAnalysis> tier3 = pickHighest(analyses,
                a -> isStronglyTrending(a) && a.activeZone() == null);
        if (tier3.isPresent()) {
            return tier3.map(PairAnalysis::pair);
        }

        Optional<PairAnalysis> tier4 = pickHighest(analyses,
                a -> a.htfConflict()
                        && a.activeZone() != null
                        && !a.activeZone().invalidated());
        if (tier4.isPresent()) {
            return tier4.map(PairAnalysis::pair);
        }

        Optional<PairAnalysis> tier5 = analyses.stream()
                .filter(a -> a.fundamental() != null
                        && a.fundamental().bias() != FundamentalAssessment.FundamentalBias.NEUTRAL)
                .max(Comparator.comparingInt(a -> a.fundamental().highImpactThisWeek() ? 1 : 0));
        if (tier5.isPresent()) {
            return tier5.map(PairAnalysis::pair);
        }

        return Optional.empty();
    }

    /**
     * Detects whether all analyses indicate consolidating markets with no
     * actionable technical or fundamental signal. Used to drive the "no
     * report consumed" path per PRD §10.2.
     */
    public boolean allMarketsConsolidating(List<PairAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return true;
        }
        for (PairAnalysis a : analyses) {
            if (a == null) {
                continue;
            }
            if (a.signalState() == SignalState.CONFIRMED
                    || a.signalState() == SignalState.AWAITING_CONFIRMATION) {
                return false;
            }
            if (isStronglyTrending(a)) {
                return false;
            }
            if (a.activeZone() != null && !a.activeZone().invalidated()) {
                return false;
            }
            if (a.fundamental() != null
                    && (a.fundamental().bias() != FundamentalAssessment.FundamentalBias.NEUTRAL
                    || a.fundamental().highImpactThisWeek())) {
                return false;
            }
        }
        return true;
    }

    private Optional<PairAnalysis> pickHighest(List<PairAnalysis> analyses,
                                               java.util.function.Predicate<PairAnalysis> filter) {
        return analyses.stream()
                .filter(filter)
                .max(Comparator
                        .comparingInt((PairAnalysis a) -> a.confidence() != null ? a.confidence().score() : 0)
                        .thenComparing(this::structureRecency));
    }

    private boolean isHighConfidence(ConfidenceScoreView confidence) {
        return confidence != null && confidence.level() == ConfidenceScoreView.ConfidenceLevel.HIGH;
    }

    private boolean isStronglyTrending(PairAnalysis a) {
        MarketStructureView w = a.structureByTimeframe().get(Timeframe.W.getCode());
        MarketStructureView d = a.structureByTimeframe().get(Timeframe.D.getCode());
        if (w == null || d == null) {
            return false;
        }
        return w.bias() == d.bias()
                && w.bias() != MarketStructureView.MarketBias.RANGING;
    }

    /**
     * Used as a tiebreaker — prefer the pair whose most-recent recognised
     * structure event happened on the most recent candle. Approximated by the
     * number of recent swings tracked on the H4 view.
     */
    private int structureRecency(PairAnalysis a) {
        MarketStructureView h4 = a.structureByTimeframe().get(Timeframe.H4.getCode());
        if (h4 == null || h4.recentSwings() == null) {
            return 0;
        }
        return h4.recentSwings().size();
    }
}
