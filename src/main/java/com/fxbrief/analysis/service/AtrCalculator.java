package com.fxbrief.analysis.service;

import com.fxbrief.analysis.dto.Candle;

import java.util.List;

final class AtrCalculator {

    private AtrCalculator() {}

    /**
     * Wilder-smoothed Average True Range. Returns 0 if there are fewer than
     * {@code period + 1} candles.
     */
    static double atr(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period + 1 || period <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 1; i <= period; i++) {
            sum += trueRange(candles.get(i), candles.get(i - 1));
        }
        double atr = sum / period;
        for (int i = period + 1; i < candles.size(); i++) {
            double tr = trueRange(candles.get(i), candles.get(i - 1));
            atr = (atr * (period - 1) + tr) / period;
        }
        return atr;
    }

    private static double trueRange(Candle current, Candle previous) {
        double range = current.high() - current.low();
        double upGap = Math.abs(current.high() - previous.close());
        double downGap = Math.abs(current.low() - previous.close());
        return Math.max(range, Math.max(upGap, downGap));
    }
}
