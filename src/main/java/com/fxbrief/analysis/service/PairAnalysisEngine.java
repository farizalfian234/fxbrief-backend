package com.fxbrief.analysis.service;

import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.Candle;
import com.fxbrief.analysis.dto.ConfidenceScoreView;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.M15ConfirmationView;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.SignalState;
import com.fxbrief.analysis.dto.Swing;
import com.fxbrief.analysis.dto.TradePlan;
import com.fxbrief.analysis.dto.Zone;
import com.fxbrief.analysis.entity.Pair;
import com.fxbrief.analysis.entity.Timeframe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the per-pair pipeline: detect swings on each timeframe, derive
 * market structure on each, find the H4 zone, check M15 confirmation, compute
 * confidence, classify the lifecycle state, derive the trade plan if confirmed,
 * and detect HTF + fundamental conflicts.
 *
 * Pure computation — no I/O, no narrative generation. The result is the
 * structured input fed to Claude downstream.
 */
@Service
@RequiredArgsConstructor
public class PairAnalysisEngine {

    private final SwingDetector swingDetector;
    private final MarketStructureAnalyzer structureAnalyzer;
    private final ZoneDetector zoneDetector;
    private final M15ConfirmationDetector m15Detector;
    private final ConfidenceScorer confidenceScorer;
    private final SignalLifecycleClassifier lifecycleClassifier;
    private final AnalysisProperties properties;

    public PairAnalysis analyze(MarketDataReader.PairMarketData marketData,
                                Map<String, FundamentalAssessment> fundamentalsByCurrency,
                                Instant now) {
        Pair pair = marketData.pair();
        Map<String, MarketStructureView> structureByTf = new LinkedHashMap<>();
        Map<Timeframe, List<Swing>> swingsByTf = new EnumMap<>(Timeframe.class);
        for (Timeframe tf : Timeframe.values()) {
            List<Candle> candles = marketData.get(tf);
            List<Swing> swings = swingDetector.detect(candles);
            swingsByTf.put(tf, swings);
            structureByTf.put(tf.getCode(), structureAnalyzer.analyze(swings));
        }

        MarketStructureView weekly = structureByTf.get(Timeframe.W.getCode());
        MarketStructureView daily = structureByTf.get(Timeframe.D.getCode());
        MarketStructureView h4 = structureByTf.get(Timeframe.H4.getCode());
        MarketStructureView m15Structure = structureByTf.get(Timeframe.M15.getCode());

        Zone activeZone = zoneDetector.detect(
                marketData.get(Timeframe.H4),
                swingsByTf.get(Timeframe.H4),
                h4);

        MarketStructureView.MarketBias htfBias = resolveHtfBias(weekly, daily);
        M15ConfirmationView m15Confirmation = m15Detector.detect(
                activeZone,
                marketData.get(Timeframe.M15),
                swingsByTf.get(Timeframe.M15),
                m15Structure,
                htfBias);

        FundamentalAssessment baseFundamental =
                fundamentalsByCurrency.get(pair.getBaseCurrency());
        FundamentalAssessment quoteFundamental =
                fundamentalsByCurrency.get(pair.getQuoteCurrency());

        boolean candlestickPattern = detectCandlestickPattern(activeZone, marketData.get(Timeframe.M15));

        ConfidenceScoreView confidence = confidenceScorer.score(
                weekly, daily, activeZone, m15Confirmation,
                baseFundamental, quoteFundamental, candlestickPattern);

        SignalState state = lifecycleClassifier.classify(activeZone, m15Confirmation, weekly, daily, now);
        boolean htfConflict = isHtfConflict(weekly, daily);
        boolean fundamentalConflict = (baseFundamental != null && baseFundamental.highImpactThisWeek())
                || (quoteFundamental != null && quoteFundamental.highImpactThisWeek());

        TradePlan tradePlan = buildTradePlan(state, activeZone, marketData);
        String layer = resolveLayer(state, activeZone, weekly, daily, baseFundamental, quoteFundamental);

        return new PairAnalysis(
                pair.getSymbol(),
                structureByTf,
                activeZone,
                confidence,
                m15Confirmation,
                state,
                tradePlan,
                pickPrimaryFundamental(baseFundamental, quoteFundamental),
                htfConflict,
                fundamentalConflict,
                null,
                layer);
    }

    private MarketStructureView.MarketBias resolveHtfBias(MarketStructureView weekly, MarketStructureView daily) {
        if (weekly == null || daily == null) {
            return MarketStructureView.MarketBias.RANGING;
        }
        if (weekly.bias() == daily.bias()) {
            return weekly.bias();
        }
        return weekly.bias();
    }

    private boolean isHtfConflict(MarketStructureView weekly, MarketStructureView daily) {
        if (weekly == null || daily == null) {
            return false;
        }
        if (weekly.bias() == MarketStructureView.MarketBias.RANGING
                || daily.bias() == MarketStructureView.MarketBias.RANGING) {
            return false;
        }
        return weekly.bias() != daily.bias();
    }

    /**
     * Looks for a simple bullish/bearish engulfing or strong pin-bar near or
     * inside the active zone on the most recent M15 candles. Used as the
     * +1 "candlestick pattern confirmation" weight in the confidence score.
     */
    private boolean detectCandlestickPattern(Zone zone, List<Candle> m15) {
        if (zone == null || zone.invalidated() || m15 == null || m15.size() < 3) {
            return false;
        }
        AnalysisProperties.M15Confirmation cfg = properties.m15Confirmation();
        int from = Math.max(0, m15.size() - 5);
        for (int i = from; i < m15.size() - 1; i++) {
            Candle c = m15.get(i + 1);
            Candle prev = m15.get(i);
            if (c.range() <= 0) {
                continue;
            }
            double bodyRatio = c.body() / c.range();
            boolean nearZone = c.low() <= zone.high() && c.high() >= zone.low();
            if (!nearZone) {
                continue;
            }
            if (bodyRatio >= cfg.engulfingMinBodyRatio()) {
                if (zone.kind() == Zone.ZoneKind.DEMAND && c.isBullish() && prev.isBearish()) {
                    return true;
                }
                if (zone.kind() == Zone.ZoneKind.SUPPLY && c.isBearish() && prev.isBullish()) {
                    return true;
                }
            }
            double wickRatio = zone.kind() == Zone.ZoneKind.DEMAND
                    ? c.lowerWick() / c.range()
                    : c.upperWick() / c.range();
            if (wickRatio >= cfg.engulfingMinBodyRatio()) {
                return true;
            }
        }
        return false;
    }

    private TradePlan buildTradePlan(SignalState state, Zone zone, MarketDataReader.PairMarketData md) {
        if (state != SignalState.CONFIRMED || zone == null || zone.invalidated()) {
            return null;
        }
        List<Candle> h4 = md.get(Timeframe.H4);
        if (h4.isEmpty()) {
            return null;
        }

        if (zone.kind() == Zone.ZoneKind.DEMAND) {
            double tp = findNearestStructureLevel(h4, zone.high(), true);
            double sl = zone.low() - (zone.high() - zone.low()) * 0.1;
            return new TradePlan(TradePlan.Direction.LONG, zone.low(), zone.high(), tp, sl);
        }
        double tp = findNearestStructureLevel(h4, zone.low(), false);
        double sl = zone.high() + (zone.high() - zone.low()) * 0.1;
        return new TradePlan(TradePlan.Direction.SHORT, zone.low(), zone.high(), tp, sl);
    }

    /**
     * Nearest swing-high above (for longs) or swing-low below (for shorts) the
     * entry level. Falls back to the most recent extreme if no clean target.
     */
    private double findNearestStructureLevel(List<Candle> h4, double fromPrice, boolean lookAbove) {
        double best = lookAbove ? Double.MAX_VALUE : -Double.MAX_VALUE;
        boolean found = false;
        int from = Math.max(0, h4.size() - 100);
        for (int i = from; i < h4.size(); i++) {
            Candle c = h4.get(i);
            if (lookAbove && c.high() > fromPrice && c.high() < best) {
                best = c.high();
                found = true;
            } else if (!lookAbove && c.low() < fromPrice && c.low() > best) {
                best = c.low();
                found = true;
            }
        }
        if (found) {
            return best;
        }
        Candle last = h4.get(h4.size() - 1);
        return lookAbove ? last.high() : last.low();
    }

    /**
     * Three-layer model per PRD §8.3: Layer 1 (Technical) if a setup or trend
     * exists; Layer 2 (Fundamental) if no technical but fundamental signal
     * exists; Layer 3 (Avoid Note) if nothing.
     */
    private String resolveLayer(SignalState state, Zone zone,
                                MarketStructureView weekly, MarketStructureView daily,
                                FundamentalAssessment base, FundamentalAssessment quote) {
        boolean hasSetup = state == SignalState.CONFIRMED
                || state == SignalState.AWAITING_CONFIRMATION
                || (state == SignalState.DETECTED && zone != null && !zone.invalidated());
        boolean trending = (weekly != null && weekly.bias() != MarketStructureView.MarketBias.RANGING)
                || (daily != null && daily.bias() != MarketStructureView.MarketBias.RANGING);
        if (hasSetup || trending) {
            return "TECHNICAL";
        }
        boolean hasFundamental = (base != null && base.bias() != FundamentalAssessment.FundamentalBias.NEUTRAL)
                || (quote != null && quote.bias() != FundamentalAssessment.FundamentalBias.NEUTRAL);
        if (hasFundamental) {
            return "FUNDAMENTAL";
        }
        return "AVOID";
    }

    private FundamentalAssessment pickPrimaryFundamental(FundamentalAssessment base, FundamentalAssessment quote) {
        if (base != null && base.highImpactThisWeek()) {
            return base;
        }
        if (quote != null && quote.highImpactThisWeek()) {
            return quote;
        }
        if (base != null && base.bias() != FundamentalAssessment.FundamentalBias.NEUTRAL) {
            return base;
        }
        if (quote != null && quote.bias() != FundamentalAssessment.FundamentalBias.NEUTRAL) {
            return quote;
        }
        return base != null ? base : quote;
    }
}
