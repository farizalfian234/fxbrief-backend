package com.fxbrief.analysis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the economic calendar refresh.
 *
 * Two entry points:
 * <ul>
 *   <li>Daily cron tick (default 22:30 UTC, just after the forex day rolls
 *       at 22:00 UTC) — unconditionally refreshes the calendar.</li>
 *   <li>{@link ApplicationReadyEvent} hook — fetches only when the cache is
 *       missing or older than the configured threshold (default 12h).
 *       Keeps cold-start latency low and avoids burning through
 *       ForexFactory's 2-per-5min rate limit during hotfix-cycle restarts.</li>
 * </ul>
 *
 * Failures are absorbed by {@link CalendarPrefetchService} — they never
 * propagate out of the scheduled methods (which would otherwise cause Spring
 * to suppress subsequent ticks).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarPrefetchScheduler {

    private final CalendarPrefetchService calendarPrefetchService;

    @Scheduled(cron = "${fxbrief.scheduler.calendar-fetch-cron}", zone = "UTC")
    public void dailyRefresh() {
        try {
            calendarPrefetchService.fetchAndStore();
        } catch (Exception e) {
            log.warn("Daily calendar refresh threw unexpectedly: {}", e.getMessage(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            calendarPrefetchService.fetchIfStale();
        } catch (Exception e) {
            log.warn("Startup calendar refresh threw unexpectedly: {}", e.getMessage(), e);
        }
    }
}
