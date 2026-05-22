package com.fxbrief.analysis.service;

import com.fxbrief.analysis.repository.EconomicCalendarCacheRepository;
import com.fxbrief.analysis.repository.MarketDataCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Wraps the native upsert queries in a separate Spring bean so each call goes
 * through the @Transactional proxy. Keeping each upsert in its own short
 * transaction avoids holding a database connection across the HTTP fetch.
 */
@Component
@RequiredArgsConstructor
public class MarketDataCacheWriter {

    private final MarketDataCacheRepository marketDataRepository;
    private final EconomicCalendarCacheRepository calendarRepository;

    @Transactional
    public void upsertMarketData(String pair, String timeframe, String payloadJson,
                                 Instant fetchedAt, UUID fetchId) {
        marketDataRepository.upsert(pair, timeframe, payloadJson, fetchedAt, fetchId);
    }

    @Transactional
    public void upsertCalendar(String scope, String payloadJson, Instant fetchedAt) {
        calendarRepository.upsert(scope, payloadJson, fetchedAt);
    }
}
