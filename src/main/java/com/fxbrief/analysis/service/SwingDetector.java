package com.fxbrief.analysis.service;

import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.Candle;
import com.fxbrief.analysis.dto.Swing;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects significant swing highs and lows in a candle series. Only swings
 * that caused a displacement-grade move qualify (PRD §7.2). The fractal pivot
 * uses {@code lookbackBars} candles on each side; the displacement filter uses
 * ATR over {@code atrPeriod} bars.
 */
@Component
public class SwingDetector {

    private final AnalysisProperties properties;

    public SwingDetector(AnalysisProperties properties) {
        this.properties = properties;
    }

    public List<Swing> detect(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }
        int lookback = properties.significantSwing().lookbackBars();
        int atrPeriod = properties.significantSwing().atrPeriod();
        double minDisplacementMultiple = properties.significantSwing().minDisplacementAtrMultiple();

        if (candles.size() < 2 * lookback + atrPeriod + 2) {
            return List.of();
        }

        double atr = AtrCalculator.atr(candles, atrPeriod);
        if (atr <= 0) {
            return List.of();
        }
        double minDisplacement = atr * minDisplacementMultiple;

        List<Swing> raw = new ArrayList<>();
        for (int i = lookback; i < candles.size() - lookback; i++) {
            Candle pivot = candles.get(i);
            if (isPivotHigh(candles, i, lookback)) {
                raw.add(new Swing(Swing.SwingType.HIGH, pivot.timestamp(), i, pivot.high()));
            } else if (isPivotLow(candles, i, lookback)) {
                raw.add(new Swing(Swing.SwingType.LOW, pivot.timestamp(), i, pivot.low()));
            }
        }

        return filterBySignificance(raw, candles, minDisplacement);
    }

    private boolean isPivotHigh(List<Candle> candles, int i, int lookback) {
        double high = candles.get(i).high();
        for (int j = 1; j <= lookback; j++) {
            if (candles.get(i - j).high() >= high) {
                return false;
            }
            if (candles.get(i + j).high() >= high) {
                return false;
            }
        }
        return true;
    }

    private boolean isPivotLow(List<Candle> candles, int i, int lookback) {
        double low = candles.get(i).low();
        for (int j = 1; j <= lookback; j++) {
            if (candles.get(i - j).low() <= low) {
                return false;
            }
            if (candles.get(i + j).low() <= low) {
                return false;
            }
        }
        return true;
    }

    /**
     * A pivot only qualifies as a significant swing if the price action between
     * this pivot and the next opposing-type pivot displaces by at least
     * {@code minDisplacement}. This filters small choppy swings.
     */
    private List<Swing> filterBySignificance(List<Swing> raw, List<Candle> candles, double minDisplacement) {
        if (raw.size() < 2) {
            return List.of();
        }
        List<Swing> result = new ArrayList<>();
        for (int i = 0; i < raw.size() - 1; i++) {
            Swing current = raw.get(i);
            Swing next = raw.get(i + 1);
            if (current.type() == next.type()) {
                continue;
            }
            double displacement = Math.abs(next.price() - current.price());
            if (displacement >= minDisplacement) {
                result.add(current);
            }
        }
        Swing last = raw.get(raw.size() - 1);
        if (!result.isEmpty()) {
            Swing lastKept = result.get(result.size() - 1);
            if (last.type() != lastKept.type()
                    && Math.abs(last.price() - lastKept.price()) >= minDisplacement) {
                result.add(last);
            }
        }
        return result;
    }
}
