package com.fxbrief.content.service;

import com.fxbrief.content.config.WeeklySummaryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Generates the weekly market recap draft every Saturday at 22:30 UTC — 30
 * minutes after the forex market closes for the week (Friday 22:00 UTC). The
 * window covered is the Monday–Friday that just closed.
 *
 * <p>On any failure the run logs and returns; there is no retry until the next
 * Saturday (PRD §9.1.3). The week-start guard makes a re-fire idempotent: a
 * draft already exists for that week, so a second run is skipped.
 *
 * <p>Single-instance assumption applies (BUILD_ASSUMPTIONS D2) — the same
 * guard prevents a second JVM from creating a duplicate draft.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklySummaryScheduler {

    private final WeeklyAggregationService aggregationService;
    private final WeeklyRecapNarrator narrator;
    private final WeeklySummaryService weeklySummaryService;
    private final SlugGenerator slugGenerator;
    private final WeeklySummaryProperties properties;
    private final Clock clock = Clock.systemUTC();

    @Scheduled(cron = "${fxbrief.scheduler.weekly-summary-cron:0 30 22 * * SAT}", zone = "UTC")
    public void generateWeeklyDraft() {
        LocalDate today = LocalDate.now(clock);
        LocalDate weekEnd = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        LocalDate weekStart = weekEnd.minusDays(properties.lookbackDays() - 1L);

        try {
            generate(weekStart, weekEnd);
        } catch (Exception e) {
            log.error("Weekly summary generation failed for week {} to {}: {}",
                    weekStart, weekEnd, e.getMessage(), e);
        }
    }

    private void generate(LocalDate weekStart, LocalDate weekEnd) {
        if (weeklySummaryService.existsForWeek(weekStart)) {
            log.info("Weekly summary already exists for week starting {}; skipping", weekStart);
            return;
        }

        String aggregate = aggregationService.buildAggregate(weekStart, weekEnd);
        if (aggregate == null) {
            log.warn("No market analysis data for week {} to {}; skipping recap", weekStart, weekEnd);
            return;
        }

        String draft = narrator.generate(weekStart, weekEnd, aggregate);
        String slug = slugGenerator.slugForWeek(weekStart);
        String title = slugGenerator.titleForWeek(weekStart);

        weeklySummaryService.createDraft(weekStart, weekEnd, title, slug, draft);
    }
}
