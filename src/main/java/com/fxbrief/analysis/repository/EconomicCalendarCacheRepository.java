package com.fxbrief.analysis.repository;

import com.fxbrief.analysis.entity.EconomicCalendarCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EconomicCalendarCacheRepository extends JpaRepository<EconomicCalendarCache, Long> {

    Optional<EconomicCalendarCache> findByScope(String scope);

    @Modifying
    @Query(value = """
            INSERT INTO economic_calendar_cache (scope, payload, fetched_at, created_at)
            VALUES (:scope, CAST(:payload AS jsonb), :fetchedAt, NOW())
            ON CONFLICT (scope)
            DO UPDATE SET payload = EXCLUDED.payload,
                          fetched_at = EXCLUDED.fetched_at
            """, nativeQuery = true)
    int upsert(@Param("scope") String scope,
               @Param("payload") String payload,
               @Param("fetchedAt") Instant fetchedAt);
}
