package com.fxbrief.analysis.service;

import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.Candle;
import com.fxbrief.analysis.dto.M15ConfirmationView;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.Swing;
import com.fxbrief.analysis.dto.Zone;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Looks for the M15 entry trigger conditions defined in PRD §7.5:
 * <ul>
 *   <li>Liquidity sweep — wick breaks a recent swing and candle closes back inside</li>
 *   <li>M15 BOS in direction of HTF bias</li>
 *   <li>Strong engulfing candle with displacement</li>
 *   <li>CHOCH on M15 in direction of HTF bias</li>
 * </ul>
 * Any one is sufficient — do not require all conditions simultaneously.
 */
@Component
public class M15ConfirmationDetector {

    private final AnalysisProperties properties;

    public M15ConfirmationDetector(AnalysisProperties properties) {
        this.properties = properties;
    }

    public M15ConfirmationView detect(Zone zone,
                                      List<Candle> m15Candles,
                                      List<Swing> m15Swings,
                                      MarketStructureView m15Structure,
                                      MarketStructureView.MarketBias htfBias) {
        if (zone == null || zone.invalidated() || m15Candles == null || m15Candles.size() < 20) {
            return M15ConfirmationView.none();
        }

        boolean sweep = detectLiquiditySweep(zone, m15Candles, m15Swings);
        boolean bos = detectM15Bos(zone, m15Structure, htfBias);
        boolean engulfing = detectEngulfingDisplacement(zone, m15Candles);
        boolean choch = detectChochAligned(m15Structure, htfBias);

        boolean confirmed = sweep || bos || engulfing || choch;
        return new M15ConfirmationView(confirmed, sweep, bos, engulfing, choch);
    }

    /**
     * Liquidity sweep — within the last {@code sweepLookbackBars} candles, a
     * candle wicks beyond the relevant recent swing and closes back inside the
     * zone (demand) or inside the recent swing range (supply).
     */
    private boolean detectLiquiditySweep(Zone zone, List<Candle> m15, List<Swing> swings) {
        if (swings == null || swings.isEmpty()) {
            return false;
        }
        int lookback = properties.m15Confirmation().sweepLookbackBars();
        int from = Math.max(0, m15.size() - lookback);

        Swing relevantSwing;
        if (zone.kind() == Zone.ZoneKind.DEMAND) {
            relevantSwing = lastOfType(swings, Swing.SwingType.LOW);
            if (relevantSwing == null) {
                return false;
            }
            for (int i = from; i < m15.size(); i++) {
                Candle c = m15.get(i);
                if (c.low() < relevantSwing.price() && c.close() > relevantSwing.price()) {
                    return true;
                }
            }
            return false;
        } else {
            relevantSwing = lastOfType(swings, Swing.SwingType.HIGH);
            if (relevantSwing == null) {
                return false;
            }
            for (int i = from; i < m15.size(); i++) {
                Candle c = m15.get(i);
                if (c.high() > relevantSwing.price() && c.close() < relevantSwing.price()) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean detectM15Bos(Zone zone, MarketStructureView m15Structure, MarketStructureView.MarketBias htfBias) {
        if (m15Structure == null) {
            return false;
        }
        MarketStructureView.StructureEvent event = m15Structure.lastEvent();
        if (zone.kind() == Zone.ZoneKind.DEMAND
                && (htfBias == MarketStructureView.MarketBias.BULLISH
                || htfBias == MarketStructureView.MarketBias.RANGING)) {
            return event == MarketStructureView.StructureEvent.BOS_BULLISH;
        }
        if (zone.kind() == Zone.ZoneKind.SUPPLY
                && (htfBias == MarketStructureView.MarketBias.BEARISH
                || htfBias == MarketStructureView.MarketBias.RANGING)) {
            return event == MarketStructureView.StructureEvent.BOS_BEARISH;
        }
        return false;
    }

    /**
     * Engulfing candle whose body fully covers the previous bar's body, in the
     * direction of the zone (bullish for demand, bearish for supply), with the
     * body of the candle exceeding {@code displacementMinAtrMultiple * ATR}.
     * The body-to-range ratio must also meet {@code engulfingMinBodyRatio}.
     */
    private boolean detectEngulfingDisplacement(Zone zone, List<Candle> m15) {
        if (m15.size() < 30) {
            return false;
        }
        double atr = AtrCalculator.atr(m15.subList(Math.max(0, m15.size() - 30), m15.size()),
                properties.significantSwing().atrPeriod());
        if (atr <= 0) {
            return false;
        }
        double minDisplacement = atr * properties.m15Confirmation().displacementMinAtrMultiple();
        double minBodyRatio = properties.m15Confirmation().engulfingMinBodyRatio();

        for (int i = m15.size() - 1; i >= Math.max(1, m15.size() - 5); i--) {
            Candle current = m15.get(i);
            Candle prev = m15.get(i - 1);
            double range = current.range();
            if (range <= 0) {
                continue;
            }
            double bodyRatio = current.body() / range;
            if (bodyRatio < minBodyRatio) {
                continue;
            }
            if (current.body() < minDisplacement) {
                continue;
            }
            if (zone.kind() == Zone.ZoneKind.DEMAND
                    && current.isBullish() && prev.isBearish()
                    && current.close() > prev.open() && current.open() < prev.close()) {
                return true;
            }
            if (zone.kind() == Zone.ZoneKind.SUPPLY
                    && current.isBearish() && prev.isBullish()
                    && current.close() < prev.open() && current.open() > prev.close()) {
                return true;
            }
        }
        return false;
    }

    private boolean detectChochAligned(MarketStructureView m15Structure, MarketStructureView.MarketBias htfBias) {
        if (m15Structure == null || m15Structure.lastEvent() == null) {
            return false;
        }
        if (htfBias == MarketStructureView.MarketBias.BULLISH) {
            return m15Structure.lastEvent() == MarketStructureView.StructureEvent.CHOCH_BULLISH;
        }
        if (htfBias == MarketStructureView.MarketBias.BEARISH) {
            return m15Structure.lastEvent() == MarketStructureView.StructureEvent.CHOCH_BEARISH;
        }
        return false;
    }

    private Swing lastOfType(List<Swing> swings, Swing.SwingType type) {
        for (int i = swings.size() - 1; i >= 0; i--) {
            if (swings.get(i).type() == type) {
                return swings.get(i);
            }
        }
        return null;
    }
}
