package com.fxbrief.report.dto;

import java.util.List;

/**
 * Static picklist for the two-step preference selector. One entry per
 * preference type, each carrying its allowed values. Values for
 * {@code FAVORITE_PAIR} are the supported pair symbols sourced from
 * {@code analysis.entity.Pair}; the other three types source their values
 * from {@code PreferenceValue}. The response has no per-user state — the
 * user's currently-saved selection comes from {@code GET /user/preferences}.
 */
public record PreferenceOptionsView(
        List<TypeOptions> types
) {

    public record TypeOptions(
            String preferenceType,
            List<String> values
    ) {}
}
