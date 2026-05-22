package com.fxbrief.analysis.service;

import com.fxbrief.analysis.dto.Candle;
import com.fxbrief.analysis.entity.EconomicCalendarCache;
import com.fxbrief.analysis.entity.MarketDataCache;
import com.fxbrief.analysis.entity.Pair;
import com.fxbrief.analysis.entity.Timeframe;
import com.fxbrief.analysis.repository.EconomicCalendarCacheRepository;
import com.fxbrief.analysis.repository.MarketDataCacheRepository;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketDataReader {

    private final MarketDataCacheRepository marketDataRepository;
    private final EconomicCalendarCacheRepository calendarRepository;
    private final CandleParser candleParser;

    @Transactional(readOnly = true)
    public PairMarketData readPair(Pair pair) {
        Map<Timeframe, List<Candle>> byTimeframe = new EnumMap<>(Timeframe.class);
        Instant latestFetch = null;
        for (Timeframe tf : Timeframe.values()) {
            MarketDataCache cache = marketDataRepository
                    .findByPairAndTimeframe(pair.getSymbol(), tf.getCode())
                    .orElseThrow(() -> new DomainException(
                            ErrorCodes.MARKET_DATA_NOT_READY,
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "No cached data for " + pair.getSymbol() + " " + tf.getCode()));
            byTimeframe.put(tf, candleParser.parse(cache.getPayload()));
            if (latestFetch == null || cache.getFetchedAt().isAfter(latestFetch)) {
                latestFetch = cache.getFetchedAt();
            }
        }
        return new PairMarketData(pair, byTimeframe, latestFetch);
    }

    @Transactional(readOnly = true)
    public Optional<CalendarSnapshot> readCalendar() {
        return calendarRepository.findByScope(EconomicCalendarCache.CURRENT_WEEK_SCOPE)
                .map(cache -> new CalendarSnapshot(cache.getPayload(), cache.getFetchedAt()));
    }

    /**
     * Resolves the currently-usable {@code fetch_id} for analysis purposes:
     * the most recent {@code fetch_id} for which all 32 (pair × timeframe)
     * cache rows agree. This is the canonical "current cycle" pointer used by
     * the shared-analysis architecture (DECISIONS D-044).
     *
     * When the most recent cycle is in flight or partially failed, some rows
     * carry the new {@code fetch_id} and some still carry the previous one.
     * In that state, this method returns the previous (fully complete) id,
     * so analysis runs against a coherent snapshot until the next cycle
     * completes entirely.
     *
     * Returns {@code Optional.empty()} if the cache is cold (no rows yet) or
     * no {@code fetch_id} has full coverage — the caller should surface
     * {@code MARKET_DATA_NOT_READY} to the user.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveCurrentFetchId() {
        List<MarketDataCache> allRows = marketDataRepository.findAll();
        if (allRows.isEmpty()) {
            return Optional.empty();
        }

        int expectedRowsPerCycle = Pair.values().length * Timeframe.values().length;
        Map<UUID, FetchIdBucket> byId = new HashMap<>();
        for (MarketDataCache row : allRows) {
            UUID id = row.getFetchId();
            if (id == null) {
                continue;
            }
            byId.computeIfAbsent(id, k -> new FetchIdBucket(k, null))
                    .recordRow(row.getFetchedAt());
        }

        return byId.values().stream()
                .filter(b -> b.rowCount == expectedRowsPerCycle)
                .max((a, b) -> a.mostRecentFetchedAt.compareTo(b.mostRecentFetchedAt))
                .map(b -> b.fetchId);
    }

    private static final class FetchIdBucket {

        private final UUID fetchId;
        private int rowCount;
        private Instant mostRecentFetchedAt;

        FetchIdBucket(UUID fetchId, Instant initial) {
            this.fetchId = fetchId;
            this.rowCount = 0;
            this.mostRecentFetchedAt = initial;
        }

        void recordRow(Instant fetchedAt) {
            this.rowCount++;
            if (this.mostRecentFetchedAt == null || fetchedAt.isAfter(this.mostRecentFetchedAt)) {
                this.mostRecentFetchedAt = fetchedAt;
            }
        }
    }

    public record PairMarketData(Pair pair, Map<Timeframe, List<Candle>> candlesByTimeframe, Instant fetchedAt) {

        public List<Candle> get(Timeframe timeframe) {
            return candlesByTimeframe.getOrDefault(timeframe, List.of());
        }
    }

    public record CalendarSnapshot(String payload, Instant fetchedAt) {}
}
