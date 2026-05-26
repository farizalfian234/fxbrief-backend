package com.fxbrief.report.service;

import com.fxbrief.analysis.dto.BestPairView;
import com.fxbrief.analysis.dto.CompactPairPreview;
import com.fxbrief.analysis.dto.ConfidenceScoreView;
import com.fxbrief.analysis.dto.EconomicEvent;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.NarrowedReportPayload;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.ReportPayload;
import com.fxbrief.analysis.dto.Swing;
import com.fxbrief.analysis.dto.Zone;
import com.fxbrief.subscription.entity.PlanCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Backend response narrower. Owns all per-plan filtering of the stored
 * {@link ReportPayload} so a Free or Basic user can never inspect their
 * network response to see content beyond their plan (D-055).
 *
 * <h2>Three call paths</h2>
 * <ul>
 *   <li>{@link #narrowForLive(ReportPayload, short)} — used by
 *       {@code POST /reports/generate} and {@code GET /reports/today}.
 *       Free/Basic responses carry {@code bestPairView} plus three
 *       {@code compactPreviews} to support the dashboard upsell.</li>
 *   <li>{@link #narrowForArchived(ReportPayload, short)} — used by
 *       {@code GET /reports/history/{reportId}}. Free/Basic responses
 *       carry {@code bestPairView} only; no compact previews, no upsell
 *       affordance. Clean reading experience per project owner's rule.</li>
 *   <li>{@link #trimForPremium(ReportPayload)} — applied to every Premium
 *       response. Drops three classes of unused-on-wire fields per the
 *       audit: the internal {@code layer} string, internal engine indices
 *       ({@code formedBarIndex}, swing {@code barIndex}), and
 *       low-importance economic events (PRD §8.3 Section 7 explicitly
 *       limits to Medium and High).</li>
 * </ul>
 *
 * <h2>Best pair view fields</h2>
 * The {@link BestPairView} returned to Free/Basic carries everything PRD
 * §8.2 specifies for both the Summary Section and the Best Pair Card:
 * pair name, daily bias, confidence level, major news risk
 * ({@code fundamental.highImpactThisWeek}), signal state, setup status,
 * short reasoning, and trade plan. Nothing else.
 *
 * <h2>Plan resolution</h2>
 * The narrower keys on the row's frozen {@code planAtGeneration}, not the
 * caller's current effective plan. This preserves the immutability rule
 * (PRD §8.1, D-054): a report generated at Basic depth on the day it was
 * created is rendered at Basic depth forever, even after the user upgrades.
 * Manual top-up may mutate {@code planAtGeneration} on today's unarchived
 * row (PRD §9, Phase 4A/5B) — that mutation is the documented exception.
 *
 * <h2>Stored payload</h2>
 * Stored {@code market_analysis.payload} JSONB is never mutated. Narrowing
 * is a pure read-time projection over the deserialised structure.
 */
@Component
public class ReportPayloadNarrower {

    private static final int COMPACT_PREVIEW_COUNT = 3;
    private static final String DAILY_TIMEFRAME = "D";

    /**
     * Narrow a payload for a live-dashboard response (today / generate).
     * <ul>
     *   <li>Premium → full trimmed {@link ReportPayload}.</li>
     *   <li>Free/Basic → {@link NarrowedReportPayload} with
     *       {@code bestPairView} and up to three {@code compactPreviews}.</li>
     * </ul>
     */
    public Object narrowForLive(ReportPayload payload, short planAtGenerationId) {
        if (isPremium(planAtGenerationId)) {
            return trimForPremium(payload);
        }
        return narrowToBestPair(payload, true);
    }

    /**
     * Narrow a payload for an archived-history response (history detail).
     * <ul>
     *   <li>Premium → full trimmed {@link ReportPayload}.</li>
     *   <li>Free/Basic → {@link NarrowedReportPayload} with
     *       {@code bestPairView} only; {@code compactPreviews} omitted.</li>
     * </ul>
     */
    public Object narrowForArchived(ReportPayload payload, short planAtGenerationId) {
        if (isPremium(planAtGenerationId)) {
            return trimForPremium(payload);
        }
        return narrowToBestPair(payload, false);
    }

    /**
     * Applies the three Premium-side trims: drop {@code layer}, drop
     * internal engine indices, filter economic events to Medium + High
     * importance only.
     */
    ReportPayload trimForPremium(ReportPayload payload) {
        if (payload == null) {
            return null;
        }
        List<PairAnalysis> trimmedPairs = payload.pairs() == null
                ? List.of()
                : payload.pairs().stream().map(this::trimPair).toList();

        return new ReportPayload(
                payload.bestPair(),
                trimmedPairs,
                payload.marketsConsolidating(),
                payload.generatedAt(),
                payload.marketDataFetchedAt(),
                payload.calendarFetchedAt());
    }

    /**
     * Build the Free/Basic narrowed payload.
     *
     * @param payload         the full engine output
     * @param includePreviews whether to populate {@code compactPreviews}
     *                        (true for live dashboard, false for archived
     *                        history)
     */
    private NarrowedReportPayload narrowToBestPair(ReportPayload payload, boolean includePreviews) {
        if (payload == null) {
            return null;
        }

        // Consolidating-markets case: no pairs, no best pair view, no previews.
        // The frontend renders this state from marketsConsolidating alone.
        if (payload.marketsConsolidating() || payload.pairs() == null || payload.pairs().isEmpty()) {
            return new NarrowedReportPayload(
                    payload.bestPair(),
                    null,
                    null,
                    payload.marketsConsolidating(),
                    payload.generatedAt(),
                    payload.marketDataFetchedAt());
        }

        PairAnalysis bestPair = findBestPair(payload);
        BestPairView bestPairView = bestPair == null ? null : toBestPairView(bestPair);

        List<CompactPairPreview> previews = null;
        if (includePreviews) {
            previews = buildCompactPreviews(payload.pairs(), bestPair);
        }

        return new NarrowedReportPayload(
                payload.bestPair(),
                bestPairView,
                previews,
                payload.marketsConsolidating(),
                payload.generatedAt(),
                payload.marketDataFetchedAt());
    }

    private PairAnalysis findBestPair(ReportPayload payload) {
        String bestPair = payload.bestPair();
        if (bestPair == null) {
            return null;
        }
        return payload.pairs().stream()
                .filter(p -> bestPair.equals(p.pair()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Maps a {@link PairAnalysis} to a {@link BestPairView}. Note that
     * {@code majorNewsRisk} comes from {@code fundamental.highImpactThisWeek}
     * per PRD §8.2 Summary Section derivation rule. When the fundamental
     * object is absent (e.g. calendar fetch failed), the risk defaults to
     * {@code false} — the safer assumption for "Major News Risk: No".
     */
    private BestPairView toBestPairView(PairAnalysis p) {
        MarketStructureView.MarketBias dailyBias = null;
        if (p.structureByTimeframe() != null) {
            MarketStructureView daily = p.structureByTimeframe().get(DAILY_TIMEFRAME);
            if (daily != null) {
                dailyBias = daily.bias();
            }
        }
        ConfidenceScoreView.ConfidenceLevel confidenceLevel =
                p.confidence() == null ? null : p.confidence().level();
        boolean majorNewsRisk =
                p.fundamental() != null && p.fundamental().highImpactThisWeek();

        return new BestPairView(
                p.pair(),
                dailyBias,
                confidenceLevel,
                majorNewsRisk,
                p.signalState(),
                p.setupStatus(),
                p.shortReasoning(),
                p.tradePlan());
    }

    /**
     * Picks three preview pairs from the non-best pairs per PRD §8.2:
     * highest, middle, lowest confidence. If fewer than three non-best pairs
     * exist, returns whatever is available.
     */
    private List<CompactPairPreview> buildCompactPreviews(List<PairAnalysis> allPairs,
                                                          PairAnalysis bestPair) {
        List<PairAnalysis> nonBest = allPairs.stream()
                .filter(p -> bestPair == null || !p.pair().equals(bestPair.pair()))
                .sorted(Comparator
                        .comparingInt(this::scoreOf)
                        .reversed())
                .toList();

        if (nonBest.isEmpty()) {
            return List.of();
        }

        List<PairAnalysis> picked = new ArrayList<>(COMPACT_PREVIEW_COUNT);
        if (nonBest.size() <= COMPACT_PREVIEW_COUNT) {
            picked.addAll(nonBest);
        } else {
            // Highest, middle, lowest by confidence score.
            picked.add(nonBest.get(0));
            picked.add(nonBest.get(nonBest.size() / 2));
            picked.add(nonBest.get(nonBest.size() - 1));
        }

        return picked.stream().map(this::toCompactPreview).toList();
    }

    private CompactPairPreview toCompactPreview(PairAnalysis p) {
        MarketStructureView.MarketBias dailyBias = null;
        if (p.structureByTimeframe() != null) {
            MarketStructureView daily = p.structureByTimeframe().get(DAILY_TIMEFRAME);
            if (daily != null) {
                dailyBias = daily.bias();
            }
        }
        return new CompactPairPreview(p.pair(), dailyBias, p.signalState());
    }

    private int scoreOf(PairAnalysis p) {
        return p.confidence() == null ? Integer.MIN_VALUE : p.confidence().score();
    }

    /**
     * Premium per-pair trim: rebuild PairAnalysis with the internal-only
     * fields stripped. {@code layer} is dropped entirely; structural composites
     * are re-projected to drop engine indices and noise-importance events.
     */
    private PairAnalysis trimPair(PairAnalysis p) {
        if (p == null) {
            return null;
        }
        return new PairAnalysis(
                p.pair(),
                trimStructureMap(p.structureByTimeframe()),
                trimZone(p.activeZone()),
                p.confidence(),
                p.m15Confirmation(),
                p.signalState(),
                p.tradePlan(),
                trimFundamental(p.fundamental()),
                p.htfConflict(),
                p.fundamentalConflict(),
                p.setupStatus(),
                p.shortReasoning(),
                p.executiveReasoning(),
                p.invalidationNote(),
                p.fundamentalSummary(),
                null);  // layer dropped per D-055 audit
    }

    private Map<String, MarketStructureView> trimStructureMap(Map<String, MarketStructureView> map) {
        if (map == null) {
            return null;
        }
        Map<String, MarketStructureView> result = new LinkedHashMap<>();
        for (Map.Entry<String, MarketStructureView> entry : map.entrySet()) {
            result.put(entry.getKey(), trimStructure(entry.getValue()));
        }
        return result;
    }

    /**
     * Strips swing {@code barIndex} (internal engine state) from each
     * recorded swing. Price and timestamp are preserved — the timestamp is
     * required for the frontend's "swing formed on April 17" labelling.
     *
     * Swing record signature is {@code (type, timestamp, barIndex, price)}.
     */
    private MarketStructureView trimStructure(MarketStructureView s) {
        if (s == null) {
            return null;
        }
        List<Swing> trimmedSwings = s.recentSwings() == null
                ? null
                : s.recentSwings().stream()
                    .map(sw -> new Swing(
                            sw.type(),
                            sw.timestamp(),
                            0,             // barIndex zeroed; stays on the wire as 0
                            sw.price()))
                    .collect(Collectors.toList());
        return new MarketStructureView(s.bias(), s.lastEvent(), trimmedSwings);
    }

    /**
     * Strips {@code formedBarIndex} from the zone. All other fields preserved.
     *
     * Zone record signature is {@code (kind, low, high, formedAt,
     * formedBarIndex, freshnessScore, freshnessState, maxPenetrationRatio,
     * penetrationDepth, fibonacciLevelsInside, invalidated,
     * invalidationReason)}.
     */
    private Zone trimZone(Zone z) {
        if (z == null) {
            return null;
        }
        return new Zone(
                z.kind(),
                z.low(),
                z.high(),
                z.formedAt(),
                0,                          // formedBarIndex zeroed
                z.freshnessScore(),         // int — comes before state
                z.freshnessState(),         // enum — comes after score
                z.maxPenetrationRatio(),
                z.penetrationDepth(),
                z.fibonacciLevelsInside(),
                z.invalidated(),
                z.invalidationReason());
    }

    /**
     * Filters {@code events} to Medium and High importance only per PRD §8.3
     * Section 7. Other fields preserved.
     */
    private FundamentalAssessment trimFundamental(FundamentalAssessment f) {
        if (f == null) {
            return null;
        }
        List<EconomicEvent> filtered = f.events() == null
                ? null
                : f.events().stream()
                    .filter(ev -> isMediumOrHigh(ev.importance()))
                    .collect(Collectors.toList());
        return new FundamentalAssessment(
                f.currency(),
                f.bias(),
                f.highImpactThisWeek(),
                filtered);
    }

    private boolean isMediumOrHigh(String importance) {
        if (importance == null) {
            return false;
        }
        String lower = importance.toLowerCase(Locale.ROOT);
        return lower.equals("medium") || lower.equals("high");
    }

    private boolean isPremium(short planId) {
        return PlanCode.PREMIUM.getId() == planId;
    }
}
