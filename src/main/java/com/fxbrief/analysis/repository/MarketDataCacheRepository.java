package com.fxbrief.analysis.repository;

import com.fxbrief.analysis.entity.MarketDataCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketDataCacheRepository extends JpaRepository<MarketDataCache, Long> {

    Optional<MarketDataCache> findByPairAndTimeframe(String pair, String timeframe);

    List<MarketDataCache> findByPair(String pair);

    /**
     * Upsert keyed on (pair, timeframe). All four mutable fields are updated
     * on conflict — including {@code fetch_id} so the row joins the new
     * cycle's identity as soon as its data lands.
     */
    @Modifying
    @Query(value = """
            INSERT INTO market_data_cache (pair, timeframe, payload, fetched_at, fetch_id, created_at)
            VALUES (:pair, :timeframe, CAST(:payload AS jsonb), :fetchedAt, :fetchId, NOW())
            ON CONFLICT (pair, timeframe)
            DO UPDATE SET payload = EXCLUDED.payload,
                          fetched_at = EXCLUDED.fetched_at,
                          fetch_id = EXCLUDED.fetch_id
            """, nativeQuery = true)
    int upsert(@Param("pair") String pair,
               @Param("timeframe") String timeframe,
               @Param("payload") String payload,
               @Param("fetchedAt") Instant fetchedAt,
               @Param("fetchId") UUID fetchId);
}
