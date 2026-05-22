package com.fxbrief.analysis.service;

import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.MarketStructureView.MarketBias;
import com.fxbrief.analysis.dto.MarketStructureView.StructureEvent;
import com.fxbrief.analysis.dto.Swing;
import com.fxbrief.analysis.dto.Swing.SwingType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Derives bias and BOS/CHOCH events from a list of significant swings.
 * Bias rule per PRD §7.2: HH+HL = Bullish, LH+LL = Bearish, otherwise Ranging.
 * BOS = price breaks in direction of prevailing trend (continuation).
 * CHOCH = price breaks against prevailing trend (reversal).
 */
@Component
public class MarketStructureAnalyzer {

    public MarketStructureView analyze(List<Swing> swings) {
        if (swings == null || swings.size() < 4) {
            return new MarketStructureView(MarketBias.RANGING, StructureEvent.NONE, List.of());
        }

        List<Swing> recent = swings.subList(Math.max(0, swings.size() - 6), swings.size());
        MarketBias bias = computeBias(recent);
        StructureEvent event = computeLastEvent(swings, bias);

        return new MarketStructureView(bias, event, recent);
    }

    private MarketBias computeBias(List<Swing> recent) {
        Swing lastHigh = lastOfType(recent, SwingType.HIGH);
        Swing prevHigh = previousOfType(recent, SwingType.HIGH);
        Swing lastLow = lastOfType(recent, SwingType.LOW);
        Swing prevLow = previousOfType(recent, SwingType.LOW);

        if (lastHigh == null || prevHigh == null || lastLow == null || prevLow == null) {
            return MarketBias.RANGING;
        }

        boolean higherHighs = lastHigh.price() > prevHigh.price();
        boolean higherLows = lastLow.price() > prevLow.price();
        boolean lowerHighs = lastHigh.price() < prevHigh.price();
        boolean lowerLows = lastLow.price() < prevLow.price();

        if (higherHighs && higherLows) {
            return MarketBias.BULLISH;
        }
        if (lowerHighs && lowerLows) {
            return MarketBias.BEARISH;
        }
        return MarketBias.RANGING;
    }

    /**
     * The "last event" is determined by the most recent swing relative to the
     * preceding same-type swing. A bullish trend that breaks a previous HH is
     * BOS_BULLISH; a bullish trend that breaks a previous HL downwards is
     * CHOCH_BEARISH. Symmetric for bearish.
     */
    private StructureEvent computeLastEvent(List<Swing> swings, MarketBias bias) {
        if (swings.size() < 2) {
            return StructureEvent.NONE;
        }
        Swing last = swings.get(swings.size() - 1);
        Swing prevSame = previousOfTypeBefore(swings, last.type(), swings.size() - 1);
        if (prevSame == null) {
            return StructureEvent.NONE;
        }
        boolean broke = (last.type() == SwingType.HIGH && last.price() > prevSame.price())
                || (last.type() == SwingType.LOW && last.price() < prevSame.price());
        if (!broke) {
            return StructureEvent.NONE;
        }

        if (bias == MarketBias.BULLISH) {
            return last.type() == SwingType.HIGH ? StructureEvent.BOS_BULLISH : StructureEvent.CHOCH_BEARISH;
        }
        if (bias == MarketBias.BEARISH) {
            return last.type() == SwingType.LOW ? StructureEvent.BOS_BEARISH : StructureEvent.CHOCH_BULLISH;
        }
        return last.type() == SwingType.HIGH ? StructureEvent.BOS_BULLISH : StructureEvent.BOS_BEARISH;
    }

    private Swing lastOfType(List<Swing> swings, SwingType type) {
        for (int i = swings.size() - 1; i >= 0; i--) {
            if (swings.get(i).type() == type) {
                return swings.get(i);
            }
        }
        return null;
    }

    private Swing previousOfType(List<Swing> swings, SwingType type) {
        int seen = 0;
        for (int i = swings.size() - 1; i >= 0; i--) {
            if (swings.get(i).type() == type) {
                seen++;
                if (seen == 2) {
                    return swings.get(i);
                }
            }
        }
        return null;
    }

    private Swing previousOfTypeBefore(List<Swing> swings, SwingType type, int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            if (swings.get(i).type() == type) {
                return swings.get(i);
            }
        }
        return null;
    }
}
