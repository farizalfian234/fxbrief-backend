package com.fxbrief.analysis.service;

import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.M15ConfirmationView;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.SignalState;
import com.fxbrief.analysis.dto.Zone;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Assigns the signal lifecycle state per PRD §7.7.
 * <ul>
 *   <li>Detected — HTF zone exists, no M15 confirmation yet (initial state)</li>
 *   <li>Awaiting Confirmation — HTF setup valid, watching M15 trigger</li>
 *   <li>Confirmed — M15 confirmation received</li>
 *   <li>Invalidated — zone or structure broken</li>
 *   <li>Expired — no M15 confirmation after 3 trading days; downgrade back to
 *       Detected if zone still fresh</li>
 * </ul>
 *
 * "Detected" vs "Awaiting Confirmation" differ in HTF context: a freshly-marked
 * zone with HTF alignment is Awaiting Confirmation (actively watched); the same
 * zone without HTF alignment but still valid is Detected (tracked).
 */
@Component
public class SignalLifecycleClassifier {

    private final AnalysisProperties properties;

    public SignalLifecycleClassifier(AnalysisProperties properties) {
        this.properties = properties;
    }

    public SignalState classify(Zone zone,
                                M15ConfirmationView m15,
                                MarketStructureView weekly,
                                MarketStructureView daily,
                                Instant now) {
        if (zone == null) {
            return SignalState.DETECTED;
        }
        if (zone.invalidated()) {
            return SignalState.INVALIDATED;
        }
        if (m15 != null && m15.confirmed()) {
            return SignalState.CONFIRMED;
        }

        Duration sinceFormed = Duration.between(zone.formedAt(), now);
        long expiryDays = properties.signalLifecycle().awaitingConfirmationExpiryDays();
        boolean htfAligned = isHtfAligned(zone, weekly, daily);

        if (sinceFormed.toDays() >= expiryDays) {
            return zone.freshnessState() == Zone.FreshnessState.FRESH
                    || zone.freshnessState() == Zone.FreshnessState.WICK_TOUCHED
                    ? SignalState.DETECTED
                    : SignalState.EXPIRED;
        }
        return htfAligned ? SignalState.AWAITING_CONFIRMATION : SignalState.DETECTED;
    }

    private boolean isHtfAligned(Zone zone, MarketStructureView weekly, MarketStructureView daily) {
        if (weekly == null || daily == null) {
            return false;
        }
        if (zone.kind() == Zone.ZoneKind.DEMAND) {
            return weekly.bias() == MarketStructureView.MarketBias.BULLISH
                    || daily.bias() == MarketStructureView.MarketBias.BULLISH;
        }
        return weekly.bias() == MarketStructureView.MarketBias.BEARISH
                || daily.bias() == MarketStructureView.MarketBias.BEARISH;
    }
}
