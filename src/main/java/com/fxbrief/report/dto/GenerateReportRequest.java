package com.fxbrief.report.dto;

import com.fxbrief.report.entity.PreferenceType;

/**
 * Optional request body for {@code POST /reports/generate}. When both
 * {@code preferenceType} and {@code preferenceValue} are non-null, they
 * act as a one-time override for this generation only — the persisted
 * {@code user_preferences} row (if any) is left untouched, and the
 * override is what lands in {@code user_reports.preference_snapshot}.
 *
 * <p>Resolution rules (DECISIONS D-056):
 * <ol>
 *   <li>Body present and both fields non-null → use the override. Validate
 *       the (type, value) pair with the same rules used for persisted
 *       preferences; reject with {@code VALIDATION_FAILED} if invalid.</li>
 *   <li>Body absent, or both fields null → read the user's persisted
 *       preference, if any.</li>
 *   <li>Neither override nor persisted preference → no compatibility
 *       scoring; {@code preference_snapshot} and {@code final_display_scores}
 *       stay null on the resulting row.</li>
 * </ol>
 *
 * <p>Partial overrides (one field set, the other null) are treated as
 * malformed and rejected with {@code VALIDATION_FAILED}. This is stricter
 * than "treat as no override" because a partial override is almost
 * certainly a frontend bug, and silently dropping it would hide the bug.
 */
public record GenerateReportRequest(
        PreferenceType preferenceType,
        String preferenceValue
) {

    public boolean hasOverride() {
        return preferenceType != null && preferenceValue != null && !preferenceValue.isBlank();
    }

    public boolean isPartial() {
        boolean typeSet = preferenceType != null;
        boolean valueSet = preferenceValue != null && !preferenceValue.isBlank();
        return typeSet != valueSet;
    }
}
