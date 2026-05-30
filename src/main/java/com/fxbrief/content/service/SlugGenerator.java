package com.fxbrief.content.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Derives slugs for content in the {@code content/} module.
 *
 * <p>{@link #slugForWeek(LocalDate)} and {@link #titleForWeek(LocalDate)} produce
 * the stable weekly-recap forms (a re-run yields the same slug, which the
 * {@code UNIQUE (slug)} constraint relies on). Examples for a week starting
 * 19 May 2026:
 * <ul>
 *   <li>slug: {@code week-of-may-19-2026}</li>
 *   <li>title: {@code FX–Brief Weekly Recap — Week of May 19, 2026}</li>
 * </ul>
 *
 * <p>{@link #slugify(String)} produces a URL-safe, lowercase, hyphenated slug
 * from arbitrary text — used by the article system for title-derived slugs.
 */
@Component
public class SlugGenerator {

    private static final String TITLE_PREFIX = "FX\u2013Brief Weekly Recap \u2014 Week of ";
    private static final int MAX_SLUG_LENGTH = 255;
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("(^-+)|(-+$)");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    public String slugForWeek(LocalDate weekStart) {
        String month = weekStart.getMonth()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                .toLowerCase(Locale.ENGLISH);
        return "week-of-%s-%d-%d".formatted(month, weekStart.getDayOfMonth(), weekStart.getYear());
    }

    public String titleForWeek(LocalDate weekStart) {
        String month = weekStart.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return TITLE_PREFIX + "%s %d, %d".formatted(month, weekStart.getDayOfMonth(), weekStart.getYear());
    }

    public String slugify(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD);
        normalized = DIACRITICS.matcher(normalized).replaceAll("");
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        String hyphenated = NON_ALNUM.matcher(lower).replaceAll("-");
        String trimmed = EDGE_HYPHENS.matcher(hyphenated).replaceAll("");
        if (trimmed.length() > MAX_SLUG_LENGTH) {
            trimmed = EDGE_HYPHENS.matcher(trimmed.substring(0, MAX_SLUG_LENGTH)).replaceAll("");
        }
        return trimmed;
    }
}
