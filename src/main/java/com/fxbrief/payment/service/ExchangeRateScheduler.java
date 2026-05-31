package com.fxbrief.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the USD/IDR rate refresh. A daily cron at 22:45 UTC (just after the forex day
 * and report-archive boundaries) refreshes unconditionally; an
 * {@link ApplicationReadyEvent} startup hook refreshes only when the cached rate is
 * missing or older than the configured staleness threshold (24 h), bounding
 * restart-driven fetches. Single-instance assumption applies (BUILD_ASSUMPTIONS D2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateScheduler {

    private final ExchangeRateService exchangeRateService;

    @Scheduled(cron = "${fxbrief.scheduler.exchange-rate-cron}")
    public void dailyRefresh() {
        log.info("Exchange rate daily refresh starting");
        exchangeRateService.refresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        exchangeRateService.refreshIfStale();
    }
}
