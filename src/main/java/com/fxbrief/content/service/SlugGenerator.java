package com.fxbrief.content.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Derives the slug and display title of a weekly recap from the week's start
 * date. Both forms are stable for a given date so a re-run produces the same
 * slug, which the {@code UNIQUE (slug)} constraint relies on.
 *
 * <p>Examples for a week starting 19 May 2026:
 * <ul>
 *   <li>slug: {@code week-of-may-19-2026}</li>
 *   <li>title: {@code FX–Brief Weekly Recap — Week of May 19, 2026}</li>
 * </ul>
 */
@Component
public class SlugGenerator {

    private static final String TITLE_PREFIX = "FX\u2013Brief Weekly Recap \u2014 Week of ";

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
}
