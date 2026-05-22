package com.fxbrief.analysis.service;

import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.Candle;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.Swing;
import com.fxbrief.analysis.dto.Zone;
import com.fxbrief.analysis.dto.Zone.FreshnessState;
import com.fxbrief.analysis.dto.Zone.ZoneKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifies the highest-quality supply or demand zone on the given candle
 * series (intended for H4 — the zone identification timeframe per PRD §7.1).
 *
 * The algorithm walks back from the most recent swing looking for the "last
 * base" — a cluster of one-or-more candles preceding an explosive move whose
 * displacement broke prior structure. Once found, the zone is scored for
 * freshness (PRD §7.3), its penetration depth is computed against subsequent
 * candles, Fibonacci levels are checked for confluence inside the zone, and
 * the invalidation rules of PRD §7.8 are applied.
 */
@Component
public class ZoneDetector {

    private final AnalysisProperties properties;

    public ZoneDetector(AnalysisProperties properties) {
        this.properties = properties;
    }

    public Zone detect(List<Candle> h4Candles, List<Swing> h4Swings, MarketStructureView h4Structure) {
        if (h4Candles == null || h4Candles.size() < 20 || h4Swings == null || h4Swings.isEmpty()) {
            return null;
        }

        double atr = AtrCalculator.atr(h4Candles, properties.significantSwing().atrPeriod());
        if (atr <= 0) {
            return null;
        }
        double minDisplacement = atr * properties.significantSwing().minDisplacementAtrMultiple();

        Zone candidate = scanForZone(h4Candles, h4Swings, minDisplacement);
        if (candidate == null) {
            return null;
        }

        FreshnessRating rating = scoreFreshness(candidate, h4Candles);
        double maxPenetration = computeMaxPenetration(candidate, h4Candles);
        Zone.PenetrationDepth penetrationDepth = classifyPenetrationDepth(maxPenetration);
        List<Double> fibLevels = computeFibonacciConfluence(candidate, h4Swings, h4Structure);
        InvalidationResult invalidation = checkInvalidation(candidate, h4Candles, h4Structure, rating);

        return new Zone(
                candidate.kind(),
                candidate.low(),
                candidate.high(),
                candidate.formedAt(),
                candidate.formedBarIndex(),
                rating.score(),
                rating.state(),
                maxPenetration,
                penetrationDepth,
                fibLevels,
                invalidation.invalidated(),
                invalidation.reason());
    }

    /**
     * Maps the raw penetration ratio into the PRD §7.3 categorical: SHALLOW
     * (still strong), MODERATE (tested but holding), DEEP (significantly
     * weakened — applies a confidence penalty in ConfidenceScorer).
     */
    private Zone.PenetrationDepth classifyPenetrationDepth(double ratio) {
        AnalysisProperties.ZonePenetration cfg = properties.zonePenetration();
        if (ratio < cfg.shallowMaxRatio()) {
            return Zone.PenetrationDepth.SHALLOW;
        }
        if (ratio >= cfg.deepMinRatio()) {
            return Zone.PenetrationDepth.DEEP;
        }
        return Zone.PenetrationDepth.MODERATE;
    }

    /**
     * Scans backwards over the swings, picking the most recent pair where the
     * subsequent move displaced beyond {@code minDisplacement} and broke the
     * structure level set by the preceding opposing swing. The "base" is the
     * highest bearish candle (for supply) or the lowest bullish candle (for
     * demand) in the small region immediately preceding the explosive move.
     */
    private Zone scanForZone(List<Candle> candles, List<Swing> swings, double minDisplacement) {
        for (int i = swings.size() - 1; i >= 1; i--) {
            Swing pivot = swings.get(i);
            Swing prior = swings.get(i - 1);
            if (pivot.type() == prior.type()) {
                continue;
            }
            double displacement = Math.abs(pivot.price() - prior.price());
            if (displacement < minDisplacement) {
                continue;
            }

            int baseEndIndex = prior.barIndex();
            int baseStartIndex = Math.max(0, baseEndIndex - 3);

            if (prior.type() == Swing.SwingType.LOW && pivot.type() == Swing.SwingType.HIGH) {
                // Bullish explosive move from a LOW pivot — demand zone at the base
                double zoneLow = Double.MAX_VALUE;
                double zoneHigh = -Double.MAX_VALUE;
                int formedAtIndex = baseEndIndex;
                boolean found = false;
                for (int b = baseEndIndex; b >= baseStartIndex; b--) {
                    Candle c = candles.get(b);
                    if (c.isBearish() || Math.abs(c.body()) <= c.range() * 0.4) {
                        zoneLow = Math.min(zoneLow, c.low());
                        zoneHigh = Math.max(zoneHigh, Math.max(c.open(), c.close()));
                        formedAtIndex = b;
                        found = true;
                    } else if (found) {
                        break;
                    }
                }
                if (!found) {
                    continue;
                }
                return new Zone(ZoneKind.DEMAND, zoneLow, zoneHigh,
                        candles.get(formedAtIndex).timestamp(), formedAtIndex,
                        0, FreshnessState.FRESH, 0.0, Zone.PenetrationDepth.MODERATE,
                        List.of(), false, null);
            }

            if (prior.type() == Swing.SwingType.HIGH && pivot.type() == Swing.SwingType.LOW) {
                // Bearish explosive move from a HIGH pivot — supply zone at the base
                double zoneLow = Double.MAX_VALUE;
                double zoneHigh = -Double.MAX_VALUE;
                int formedAtIndex = baseEndIndex;
                boolean found = false;
                for (int b = baseEndIndex; b >= baseStartIndex; b--) {
                    Candle c = candles.get(b);
                    if (c.isBullish() || Math.abs(c.body()) <= c.range() * 0.4) {
                        zoneLow = Math.min(zoneLow, Math.min(c.open(), c.close()));
                        zoneHigh = Math.max(zoneHigh, c.high());
                        formedAtIndex = b;
                        found = true;
                    } else if (found) {
                        break;
                    }
                }
                if (!found) {
                    continue;
                }
                return new Zone(ZoneKind.SUPPLY, zoneLow, zoneHigh,
                        candles.get(formedAtIndex).timestamp(), formedAtIndex,
                        0, FreshnessState.FRESH, 0.0, Zone.PenetrationDepth.MODERATE,
                        List.of(), false, null);
            }
        }
        return null;
    }

    /**
     * Per PRD §7.3 — never retested +3, wick touch +1, body close inside -2,
     * multiple candles inside -4, full close beyond = invalidate. The walk
     * starts at the first candle after the zone formed and accumulates the
     * highest-cost interaction seen.
     */
    private FreshnessRating scoreFreshness(Zone candidate, List<Candle> candles) {
        AnalysisProperties.ZoneFreshness w = properties.zoneFreshness();
        int score = w.neverRetestedScore();
        FreshnessState state = FreshnessState.FRESH;
        int bodyClosesInside = 0;
        boolean wickTouched = false;

        for (int i = candidate.formedBarIndex() + 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            boolean wickTouches = c.low() <= candidate.high() && c.high() >= candidate.low();
            boolean bodyInside = bodyOverlapsZone(c, candidate);
            boolean fullClosedBeyond = candidate.kind() == ZoneKind.DEMAND
                    ? c.close() < candidate.low() && c.open() < candidate.low()
                    : c.close() > candidate.high() && c.open() > candidate.high();

            if (fullClosedBeyond) {
                return new FreshnessRating(w.invalidationThreshold(), FreshnessState.INVALIDATED);
            }
            if (bodyInside) {
                bodyClosesInside++;
                if (bodyClosesInside >= 2) {
                    score = w.neverRetestedScore() + w.multipleCandlesInsideScore();
                    state = FreshnessState.WEAKENED;
                } else {
                    score = w.neverRetestedScore() + w.oneBodyCloseInsideScore();
                    if (state.compareTo(FreshnessState.BODY_TOUCHED) < 0) {
                        state = FreshnessState.BODY_TOUCHED;
                    }
                }
            } else if (wickTouches) {
                wickTouched = true;
            }
        }

        if (state == FreshnessState.FRESH && wickTouched) {
            score = w.neverRetestedScore() + w.wickTouchScore();
            state = FreshnessState.WICK_TOUCHED;
        }
        return new FreshnessRating(score, state);
    }

    private boolean bodyOverlapsZone(Candle c, Zone zone) {
        double bodyLow = Math.min(c.open(), c.close());
        double bodyHigh = Math.max(c.open(), c.close());
        return bodyLow < zone.high() && bodyHigh > zone.low();
    }

    /**
     * Maximum penetration ratio (0.0-1.0) — how far into the zone any single
     * subsequent candle pushed before reversing. ≤20% is shallow (acceptable);
     * ≥80% significantly weakens the zone (PRD §7.3).
     */
    private double computeMaxPenetration(Zone zone, List<Candle> candles) {
        double depth = zone.high() - zone.low();
        if (depth <= 0) {
            return 0.0;
        }
        double maxPenetration = 0.0;
        for (int i = zone.formedBarIndex() + 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double penetration;
            if (zone.kind() == ZoneKind.DEMAND) {
                if (c.low() >= zone.high()) {
                    continue;
                }
                penetration = (zone.high() - Math.max(c.low(), zone.low())) / depth;
            } else {
                if (c.high() <= zone.low()) {
                    continue;
                }
                penetration = (Math.min(c.high(), zone.high()) - zone.low()) / depth;
            }
            penetration = Math.max(0.0, Math.min(1.0, penetration));
            if (penetration > maxPenetration) {
                maxPenetration = penetration;
            }
        }
        return maxPenetration;
    }

    /**
     * Fibonacci levels are computed against the most recent leg defined by the
     * last two opposing swings. Levels falling inside the zone (within a small
     * tolerance) are returned. Per PRD §7.4 these are used as confluence inside
     * the zone — never standalone.
     */
    private List<Double> computeFibonacciConfluence(Zone zone, List<Swing> swings, MarketStructureView structure) {
        if (swings.size() < 2) {
            return List.of();
        }
        Swing last = swings.get(swings.size() - 1);
        Swing prior = null;
        for (int i = swings.size() - 2; i >= 0; i--) {
            if (swings.get(i).type() != last.type()) {
                prior = swings.get(i);
                break;
            }
        }
        if (prior == null) {
            return List.of();
        }
        double swingLow = Math.min(last.price(), prior.price());
        double swingHigh = Math.max(last.price(), prior.price());
        double range = swingHigh - swingLow;
        if (range <= 0) {
            return List.of();
        }

        boolean strongTrend = structure != null
                && structure.bias() != MarketStructureView.MarketBias.RANGING;
        List<Double> levels = strongTrend
                ? properties.fibonacci().strongTrendLevels()
                : properties.fibonacci().deepPullbackLevels();

        double tolerance = range * properties.fibonacci().levelMatchToleranceRatio();
        List<Double> matches = new ArrayList<>();
        for (Double level : levels) {
            double price = swingHigh - level * range;
            if (price >= zone.low() - tolerance && price <= zone.high() + tolerance) {
                matches.add(level);
            }
        }
        return matches;
    }

    /**
     * PRD §7.8 — demand invalid if H4 closes below zone low, supply invalid
     * if H4 closes above zone high. Multi-candle acceptance also invalidates.
     */
    private InvalidationResult checkInvalidation(Zone zone, List<Candle> candles,
                                                 MarketStructureView structure,
                                                 FreshnessRating rating) {
        if (rating.state() == FreshnessState.INVALIDATED) {
            return new InvalidationResult(true, "Full candle close beyond zone");
        }
        for (int i = zone.formedBarIndex() + 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            if (zone.kind() == ZoneKind.DEMAND && c.close() < zone.low()) {
                return new InvalidationResult(true, "H4 candle closed below demand zone low");
            }
            if (zone.kind() == ZoneKind.SUPPLY && c.close() > zone.high()) {
                return new InvalidationResult(true, "H4 candle closed above supply zone high");
            }
        }
        if (structure != null) {
            if (zone.kind() == ZoneKind.DEMAND
                    && structure.lastEvent() == MarketStructureView.StructureEvent.CHOCH_BEARISH) {
                return new InvalidationResult(true, "Bearish CHOCH on H4 against demand zone");
            }
            if (zone.kind() == ZoneKind.SUPPLY
                    && structure.lastEvent() == MarketStructureView.StructureEvent.CHOCH_BULLISH) {
                return new InvalidationResult(true, "Bullish CHOCH on H4 against supply zone");
            }
        }
        return new InvalidationResult(false, null);
    }

    private record FreshnessRating(int score, FreshnessState state) {}

    private record InvalidationResult(boolean invalidated, String reason) {}
}
