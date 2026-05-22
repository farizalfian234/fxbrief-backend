package com.fxbrief.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.client.ForexFactoryCalendarClient;
import com.fxbrief.analysis.config.ForexFactoryProperties;
import com.fxbrief.analysis.entity.EconomicCalendarCache;
import com.fxbrief.analysis.repository.EconomicCalendarCacheRepository;
import com.fxbrief.common.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Owns the economic calendar pre-fetch cycle. Calls
 * {@link ForexFactoryCalendarClient}, serialises the payload, and upserts the
 * single {@code economic_calendar_cache} row keyed by scope
 * {@code CURRENT_WEEK}.
 *
 * Two entry points exist: the daily scheduler tick (always fetches) and the
 * conditional startup-hook fetch (fetches only when the local cache is missing
 * or older than {@link ForexFactoryProperties#startupRefreshThresholdHours()}).
 * The threshold guard exists because ForexFactory enforces a 2-downloads-per-5-
 * minutes rate limit across all file formats — without the guard, rapid
 * restarts during a hotfix session would burn through that limit and start
 * receiving HTML "Request Denied" pages.
 *
 * Neither entry point is itself {@code @Transactional}. The cache write goes
 * through {@link MarketDataCacheWriter#upsertCalendar}, which carries its own
 * {@code @Transactional} annotation and runs in its own short transaction per
 * the writer-bean pattern (DECISIONS D-039). Wrapping an outer transaction
 * around either entry point would either (a) hold a DB connection across the
 * ForexFactory HTTP call, or (b) — if marked read-only — cause the inner
 * upsert to inherit the read-only mode and fail with Postgres
 * "cannot execute INSERT in a read-only transaction".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarPrefetchService {

    private final ForexFactoryCalendarClient calendarClient;
    private final EconomicCalendarCacheRepository calendarRepository;
    private final MarketDataCacheWriter cacheWriter;
    private final ObjectMapper objectMapper;
    private final ForexFactoryProperties properties;

    /**
     * Always fetch and store, regardless of cache freshness. Called by the
     * daily scheduler.
     */
    public boolean fetchAndStore() {
        try {
            JsonNode payload = calendarClient.fetchCalendar();
            String json = objectMapper.writeValueAsString(payload);
            cacheWriter.upsertCalendar(EconomicCalendarCache.CURRENT_WEEK_SCOPE, json, Instant.now());
            log.info("Economic calendar refreshed from ForexFactory ({} events)",
                    payload.size());
            return true;
        } catch (DomainException e) {
            log.warn("Economic calendar fetch failed: code={} message={}", e.getCode(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Economic calendar fetch threw unexpectedly: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Fetch only when the cache is missing or older than the configured
     * startup-refresh threshold. Called by the {@code ApplicationReadyEvent}
     * hook.
     *
     * The {@code findByScope} read is a single auto-committed statement
     * issued by Spring Data — no enclosing transaction needed. The
     * conditional {@code fetchAndStore()} branch writes through the
     * transactional writer bean.
     *
     * @return {@code true} when a network fetch happened (success or failure),
     *         {@code false} when the call was skipped because the cache is
     *         still fresh.
     */
    public boolean fetchIfStale() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(properties.startupRefreshThresholdHours()));
        return calendarRepository.findByScope(EconomicCalendarCache.CURRENT_WEEK_SCOPE)
                .filter(row -> row.getFetchedAt().isAfter(cutoff))
                .map(row -> {
                    log.info("Calendar cache fresh (fetched {}), skipping startup refresh", row.getFetchedAt());
                    return false;
                })
                .orElseGet(() -> {
                    log.info("Calendar cache missing or stale, refreshing on startup");
                    fetchAndStore();
                    return true;
                });
    }
}
