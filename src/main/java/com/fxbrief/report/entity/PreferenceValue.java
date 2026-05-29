package com.fxbrief.report.entity;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Enumerates every preference value <em>except</em> pair symbols. Pair
 * symbols ({@code "EUR/USD"}, etc.) are validated against
 * {@code com.fxbrief.analysis.entity.Pair} since they are already the source
 * of truth there — duplicating the eight symbols here would create a drift
 * risk if the supported-pair list ever changes.
 *
 * <p>Validation rules:
 *
 * <ul>
 *   <li>For {@link PreferenceType#TRADING_STYLE}, {@link PreferenceType#PREFERRED_SESSION},
 *       and {@link PreferenceType#RISK_PROFILE}: the stored
 *       {@code user_preferences.preference_value} string must match a
 *       {@code PreferenceValue} enum name and that value's {@link #type()}
 *       must match the row's {@code preference_type}.</li>
 *   <li>For {@link PreferenceType#FAVORITE_PAIR}: the stored
 *       {@code preference_value} string must match
 *       {@code com.fxbrief.analysis.entity.Pair#fromSymbol(...)} and is
 *       not present in this enum.</li>
 * </ul>
 *
 * <p>The {@code PreferenceResolver} centralises this validation so callers
 * (the request body, the persisted row) get the same answer either way.
 */
public enum PreferenceValue {

    // TRADING_STYLE
    SCALPER(PreferenceType.TRADING_STYLE),
    INTRADAY(PreferenceType.TRADING_STYLE),
    SWING_TRADER(PreferenceType.TRADING_STYLE),
    POSITION_TRADER(PreferenceType.TRADING_STYLE),

    // PREFERRED_SESSION
    ASIAN(PreferenceType.PREFERRED_SESSION),
    LONDON(PreferenceType.PREFERRED_SESSION),
    NEW_YORK(PreferenceType.PREFERRED_SESSION),

    // RISK_PROFILE
    CONSERVATIVE(PreferenceType.RISK_PROFILE),
    BALANCED(PreferenceType.RISK_PROFILE),
    AGGRESSIVE(PreferenceType.RISK_PROFILE);

    private final PreferenceType type;

    PreferenceValue(PreferenceType type) {
        this.type = type;
    }

    public PreferenceType type() {
        return type;
    }

    /**
     * Returns the {@code PreferenceValue} matching the given enum-name string
     * under the given preference type. Empty when the string does not match
     * an enum name, or the matched value belongs to a different type. Always
     * empty for {@link PreferenceType#FAVORITE_PAIR} (use {@code Pair.fromSymbol}
     * instead).
     */
    public static Optional<PreferenceValue> resolve(PreferenceType type, String value) {
        if (type == null || value == null || type == PreferenceType.FAVORITE_PAIR) {
            return Optional.empty();
        }
        try {
            PreferenceValue candidate = PreferenceValue.valueOf(value);
            return candidate.type == type ? Optional.of(candidate) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The set of values valid for a given preference type. Used by tests and
     * by the validator when reporting the allowed set in an error message.
     * Empty for {@link PreferenceType#FAVORITE_PAIR} since its values are
     * pair symbols, not enum names.
     */
    public static Set<PreferenceValue> valuesFor(PreferenceType type) {
        if (type == null || type == PreferenceType.FAVORITE_PAIR) {
            return Set.of();
        }
        EnumSet<PreferenceValue> result = EnumSet.noneOf(PreferenceValue.class);
        for (PreferenceValue v : values()) {
            if (v.type == type) {
                result.add(v);
            }
        }
        return result;
    }
}
