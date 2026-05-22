package com.fxbrief.analysis.service;

import com.fxbrief.subscription.service.ForexMarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the market-data pre-fetch cycle. The Spring scheduler invokes
 * {@link #runPrefetchCycle()} on a fixed delay (default 65 minutes). The first
 * tick fires with the configured initial delay (default 0) — combined with the
 * market-hours gate inside the method, this produces the PRD-required
 * behaviour: "immediate fetch on app startup if within market hours, then
 * every 65 minutes after".
 *
 * fixedDelay (rather than fixedRate) is deliberate: the next fetch starts only
 * after the previous one completes, preventing overlap if a fetch is slow.
 *
 * Failures are absorbed by {@link MarketDataPrefetchService} — they never
 * propagate out of the scheduled method (which would otherwise suppress
 * subsequent ticks).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataPrefetchScheduler {

    private final MarketDataPrefetchService prefetchService;
    private final ForexMarketClock forexMarketClock;

    @Scheduled(
            fixedDelayString = "${fxbrief.scheduler.market-data-fetch-interval-ms}",
            initialDelayString = "${fxbrief.scheduler.market-data-fetch-initial-delay-ms}")
    public void runPrefetchCycle() {
        if (!forexMarketClock.isMarketOpen()) {
            log.debug("Forex market closed — skipping pre-fetch cycle");
            return;
        }
        try {
            prefetchService.executeFetchCycle();
        } catch (Exception e) {
            log.warn("Pre-fetch cycle threw unexpectedly: {}", e.getMessage(), e);
        }
    }
}
