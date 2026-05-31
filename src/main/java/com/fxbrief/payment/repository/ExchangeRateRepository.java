package com.fxbrief.payment.repository;

import com.fxbrief.payment.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Repository
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByScope(String scope);

    /**
     * Upserts the single rate row in place. The unique {@code scope} sentinel guarantees
     * one row; a second fetch updates the value and timestamp rather than inserting a new
     * row, mirroring the economic_calendar_cache pattern.
     */
    @Modifying
    @Query(value = """
            INSERT INTO exchange_rates (scope, usd_to_idr, fetched_at, created_at)
            VALUES (:scope, :usdToIdr, :fetchedAt, now())
            ON CONFLICT (scope)
            DO UPDATE SET usd_to_idr = EXCLUDED.usd_to_idr,
                          fetched_at = EXCLUDED.fetched_at
            """, nativeQuery = true)
    void upsert(@Param("scope") String scope,
                @Param("usdToIdr") BigDecimal usdToIdr,
                @Param("fetchedAt") Instant fetchedAt);
}
