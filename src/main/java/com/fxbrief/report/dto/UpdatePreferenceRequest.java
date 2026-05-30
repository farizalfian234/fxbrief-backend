package com.fxbrief.report.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code PUT /user/preferences}. Both fields are required; the
 * (type, value) pair is validated against the {@code PreferenceType} /
 * {@code PreferenceValue} enums (and {@code Pair.fromSymbol} for
 * {@code FAVORITE_PAIR}) in {@code UserPreferenceService}.
 */
public record UpdatePreferenceRequest(
        @NotBlank String preferenceType,
        @NotBlank String preferenceValue
) {}
