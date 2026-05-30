package com.fxbrief.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The user's currently-saved preference. When no preference row exists,
 * {@code set} is false and both fields are null and omitted from the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserPreferenceView(
        boolean set,
        String preferenceType,
        String preferenceValue
) {

    public static UserPreferenceView none() {
        return new UserPreferenceView(false, null, null);
    }

    public static UserPreferenceView of(String preferenceType, String preferenceValue) {
        return new UserPreferenceView(true, preferenceType, preferenceValue);
    }
}
