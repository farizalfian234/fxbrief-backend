package com.fxbrief.report.service;

import com.fxbrief.report.repository.UserReportRepository;
import com.fxbrief.subscription.service.ForexMarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily 22:00 UTC scheduler that flips every unarchived {@code user_reports}
 * row from the prior forex market day into the archived state. Once
 * archived, the row is immutable — PRD §8.1 specifies that history reports
 * cannot be upgraded retroactively, so their {@code plan_at_generation}
 * badge is frozen here.
 *
 * Runs at 22:00 UTC exactly, the same boundary as the forex market day
 * (PRD §5.3). The cron expression is configurable via
 * {@code fxbrief.scheduler.report-archive-cron}.
 *
 * Single-instance assumption applies (BUILD_ASSUMPTIONS D2) — a second JVM
 * would fire the job again, idempotently archiving an already-archived
 * set; the {@code WHERE is_archived = false} predicate makes the second
 * call a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportArchivingScheduler {

    private final UserReportRepository userReportRepository;
    private final ForexMarketClock forexMarketClock;

    @Scheduled(cron = "${fxbrief.scheduler.report-archive-cron:0 0 22 * * *}", zone = "UTC")
    @Transactional
    public void archivePriorForexDay() {
        // The scheduler fires at exactly 22:00 UTC, which is the boundary where
        // the new forex market date begins. Archive everything strictly before
        // the new date — i.e. the day that just ended.
        LocalDate cutoff = forexMarketClock.currentForexMarketDate().minusDays(1);
        Instant now = Instant.now();
        int archived = userReportRepository.archiveOnOrBefore(cutoff, now);
        log.info("Report archive run: cutoff={} archived={} rows", cutoff, archived);
    }
}
