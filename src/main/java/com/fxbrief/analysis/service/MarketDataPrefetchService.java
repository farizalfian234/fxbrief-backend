package com.fxbrief.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.client.TwelveDataClient;
import com.fxbrief.analysis.entity.Pair;
import com.fxbrief.analysis.entity.Timeframe;
import com.fxbrief.common.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates a single market-data pre-fetch cycle.
 *
 * Per cycle: 4 HTTP calls (one per timeframe), each requesting all 8 pairs in
 * a single batched {@code /time_series} call. Each batch consumes 8 Twelve
 * Data credits — exactly the free-tier per-minute budget — so this method
 * paces successive batches at least
 * {@code fxbrief.scheduler.market-data-batch-pacing-seconds} seconds apart.
 * Default 65s gives a 5-second safety margin over Twelve Data's 60s reset.
 *
 * <h2>Cycle identity</h2>
 * Each invocation of {@link #executeFetchCycle()} mints a {@code UUID} cycle
 * id. Every row upserted in this cycle carries that id in
 * {@code market_data_cache.fetch_id}. This lets the analysis engine resolve
 * the "current fetch" as the most recent id where all 32 cache rows agree
 * (PRD §11.2 v3, DECISIONS D-044 shared-analysis architecture).
 *
 * <h2>Failure semantics</h2>
 * Failures are logged and contained: a transient Twelve Data error on one
 * timeframe leaves the other three intact, and the previous cached payload
 * remains untouched. When the client throws
 * {@link TwelveDataClient.RateLimitedException}, we honor the server's
 * Retry-After hint by sleeping that long before proceeding to the next batch.
 * A partially failed cycle leaves a mixed-fetch-id state in the cache; the
 * engine's "all 32 rows must share fetch_id" check naturally falls back to
 * the previous fully-complete cycle until the next cycle succeeds entirely.
 *
 * Upserts go through {@link MarketDataCacheWriter} so each runs in its own
 * short transaction, never holding a connection across the HTTP fetch.
 */
@Slf4j
@Service
public class MarketDataPrefetchService {

    private final TwelveDataClient twelveDataClient;
    private final MarketDataCacheWriter cacheWriter;
    private final ObjectMapper objectMapper;
    private final long pacingMillis;

    public MarketDataPrefetchService(TwelveDataClient twelveDataClient,
                                     MarketDataCacheWriter cacheWriter,
                                     ObjectMapper objectMapper,
                                     @Value("${fxbrief.scheduler.market-data-batch-pacing-seconds:65}")
                                     int pacingSeconds) {
        this.twelveDataClient = twelveDataClient;
        this.cacheWriter = cacheWriter;
        this.objectMapper = objectMapper;
        this.pacingMillis = Math.max(0, pacingSeconds) * 1000L;
    }

    public PrefetchSummary executeFetchCycle() {
        UUID fetchId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        int pairsUpdated = 0;
        int timeframeFailures = 0;

        log.info("Starting pre-fetch cycle id={}", fetchId);

        Timeframe[] timeframes = Timeframe.values();
        for (int i = 0; i < timeframes.length; i++) {
            Timeframe timeframe = timeframes[i];
            try {
                pairsUpdated += fetchAndStoreTimeframe(timeframe, fetchId);
            } catch (TwelveDataClient.RateLimitedException e) {
                timeframeFailures++;
                log.warn("Market data fetch rate-limited for timeframe {}: retry-after {}s",
                        timeframe.getCode(), e.getRetryAfterSeconds());
                sleepQuietly(e.getRetryAfterSeconds() * 1000L);
                continue;
            } catch (DomainException e) {
                timeframeFailures++;
                log.warn("Market data fetch failed for timeframe {}: code={} message={}",
                        timeframe.getCode(), e.getCode(), e.getMessage());
            }

            boolean isLast = (i == timeframes.length - 1);
            if (!isLast && pacingMillis > 0) {
                log.debug("Pacing {}ms before next OHLCV batch", pacingMillis);
                sleepQuietly(pacingMillis);
            }
        }

        PrefetchSummary summary = new PrefetchSummary(
                fetchId, startedAt, Instant.now(), pairsUpdated, timeframeFailures);

        log.info("Pre-fetch cycle complete: id={} pairsUpdated={} timeframeFailures={} durationMs={}",
                summary.fetchId(), summary.pairsUpdated(), summary.timeframeFailures(),
                Duration.between(summary.startedAt(), summary.completedAt()).toMillis());

        return summary;
    }

    private int fetchAndStoreTimeframe(Timeframe timeframe, UUID fetchId) {
        Map<String, JsonNode> bySymbol = twelveDataClient.fetchOhlcvBatch(timeframe);
        if (bySymbol.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int count = 0;
        for (Pair pair : Pair.values()) {
            JsonNode payload = bySymbol.get(pair.getSymbol());
            if (payload == null) {
                log.warn("Twelve Data batch missing pair {} at {}", pair.getSymbol(), timeframe.getCode());
                continue;
            }
            try {
                String json = objectMapper.writeValueAsString(payload);
                cacheWriter.upsertMarketData(pair.getSymbol(), timeframe.getCode(), json, now, fetchId);
                count++;
            } catch (Exception e) {
                log.warn("Failed to upsert market data for {} {}: {}",
                        pair.getSymbol(), timeframe.getCode(), e.getMessage());
            }
        }
        return count;
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Pre-fetch pacing sleep interrupted");
        }
    }

    public record PrefetchSummary(
            UUID fetchId,
            Instant startedAt,
            Instant completedAt,
            int pairsUpdated,
            int timeframeFailures
    ) {}
}
