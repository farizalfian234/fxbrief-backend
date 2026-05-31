package com.fxbrief.payment.service;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.payment.client.ExchangeRateClient;
import com.fxbrief.payment.config.ExchangeRateProperties;
import com.fxbrief.payment.dto.ExchangeRateView;
import com.fxbrief.payment.entity.ExchangeRate;
import com.fxbrief.payment.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Owns the single USD/IDR rate row: fetching from open.er-api.com, persisting via
 * upsert, and serving the current rate to the payment flow and the public endpoint.
 *
 * <p>A failed fetch never clears the cached rate — the existing row is preserved so the
 * payment flow keeps working on the last known rate (the Resilience4j retry on the client
 * absorbs transient failures first).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private final ExchangeRateClient exchangeRateClient;
    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateProperties properties;

    /**
     * Fetches the latest rate and upserts the single row. On fetch failure the cached
     * row is left untouched and the failure is logged — callers continue on the last
     * known rate.
     */
    @Transactional
    public void refresh() {
        try {
            BigDecimal rate = exchangeRateClient.fetchUsdToIdr();
            exchangeRateRepository.upsert(ExchangeRate.SCOPE_CURRENT, rate, Instant.now());
            log.info("Exchange rate refreshed: USD/IDR={}", rate);
        } catch (DomainException e) {
            log.warn("Exchange rate refresh failed, keeping existing cached rate: {}", e.getMessage());
        }
    }

    /**
     * Startup hook helper: fetches only when no row exists or the cached row is older
     * than the configured staleness threshold.
     */
    @Transactional
    public void refreshIfStale() {
        Optional<ExchangeRate> current = exchangeRateRepository.findByScope(ExchangeRate.SCOPE_CURRENT);
        if (current.isPresent() && !isStale(current.get().getFetchedAt())) {
            log.info("Cached exchange rate is fresh, skipping startup fetch");
            return;
        }
        refresh();
    }

    @Transactional(readOnly = true)
    public ExchangeRateView getCurrentRate() {
        ExchangeRate rate = requireCurrentRate();
        return new ExchangeRateView(rate.getUsdToIdr(), rate.getFetchedAt());
    }

    @Transactional(readOnly = true)
    public ExchangeRate requireCurrentRate() {
        return exchangeRateRepository.findByScope(ExchangeRate.SCOPE_CURRENT)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.EXCHANGE_RATE_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Exchange rate is not yet available"));
    }

    private boolean isStale(Instant fetchedAt) {
        Duration threshold = Duration.ofHours(properties.startupRefreshThresholdHours());
        return fetchedAt.isBefore(Instant.now().minus(threshold));
    }
}
