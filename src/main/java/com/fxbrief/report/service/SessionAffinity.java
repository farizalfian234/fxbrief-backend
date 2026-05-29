package com.fxbrief.report.service;

import com.fxbrief.report.entity.PreferenceValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pair-to-trading-session affinity used by {@code PreferenceScorer} when
 * scoring the {@code PREFERRED_SESSION} preference type.
 *
 * <p>High-affinity pairs per session (Addition 3 spec):
 * <ul>
 *   <li>{@link PreferenceValue#ASIAN}: USD/JPY, AUD/USD, NZD/USD</li>
 *   <li>{@link PreferenceValue#LONDON}: EUR/USD, GBP/USD, USD/CHF</li>
 *   <li>{@link PreferenceValue#NEW_YORK}: EUR/USD, USD/CAD, XAU/USD</li>
 * </ul>
 *
 * <p>Scoring (spec): {@code 1.0} for high affinity, {@code 0.5} for medium,
 * {@code 0.0} for low. A pair is "high" when it appears in the session's
 * set above; "medium" when it appears in some other session's set but not
 * this one; "low" when it appears in no session's set. With the current
 * 8-pair universe every pair appears in at least one session's set, so
 * the {@code 0.0} branch is unused today — kept for forward flexibility
 * if an exotic pair is added that doesn't fit any session.
 */
final class SessionAffinity {

    private static final Map<PreferenceValue, Set<String>> HIGH_BY_SESSION;

    static {
        HIGH_BY_SESSION = new HashMap<>();
        HIGH_BY_SESSION.put(PreferenceValue.ASIAN,
                Set.of("USD/JPY", "AUD/USD", "NZD/USD"));
        HIGH_BY_SESSION.put(PreferenceValue.LONDON,
                Set.of("EUR/USD", "GBP/USD", "USD/CHF"));
        HIGH_BY_SESSION.put(PreferenceValue.NEW_YORK,
                Set.of("EUR/USD", "USD/CAD", "XAU/USD"));
    }

    private SessionAffinity() {}

    /**
     * Returns the affinity score in {@code [0.0, 1.0]} for the given pair
     * under the given session.
     *
     * @throws IllegalArgumentException if {@code session} is not one of the
     *         three PREFERRED_SESSION values
     */
    static double score(PreferenceValue session, String pairSymbol) {
        Set<String> highForThisSession = HIGH_BY_SESSION.get(session);
        if (highForThisSession == null) {
            throw new IllegalArgumentException(
                    "Not a PREFERRED_SESSION value: " + session);
        }
        if (highForThisSession.contains(pairSymbol)) {
            return 1.0;
        }
        for (Set<String> set : HIGH_BY_SESSION.values()) {
            if (set.contains(pairSymbol)) {
                return 0.5;
            }
        }
        return 0.0;
    }
}
