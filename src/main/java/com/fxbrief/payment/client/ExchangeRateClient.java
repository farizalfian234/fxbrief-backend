package com.fxbrief.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.payment.config.ExchangeRateProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Fetches the current USD/IDR rate from open.er-api.com. The endpoint is free and
 * unauthenticated. All transient HTTP/network errors are translated to a
 * {@link DomainException} before they reach the Resilience4j aspect, matching the
 * other outbound clients (D-041). The retry/circuit-breaker instance is
 * {@code exchangeRate}.
 */
@Slf4j
@Component
public class ExchangeRateClient {

    public static final String RESILIENCE_NAME = "exchangeRate";

    private static final String LATEST_USD_PATH = "/v6/latest/USD";

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ExchangeRateClient(ExchangeRateProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(buildRequestFactory(properties))
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(ExchangeRateProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return factory;
    }

    @Retry(name = RESILIENCE_NAME)
    @CircuitBreaker(name = RESILIENCE_NAME)
    public BigDecimal fetchUsdToIdr() {
        try {
            String responseBody = restClient.get()
                    .uri(LATEST_USD_PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return parseIdrRate(responseBody);
        } catch (HttpClientErrorException e) {
            log.warn("Exchange-rate client error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw fetchFailure("Exchange-rate client error " + e.getStatusCode());
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("Exchange-rate transient failure: {}", e.getMessage());
            throw fetchFailure("Exchange-rate transient failure: " + e.getMessage());
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Exchange-rate unexpected failure: {}", e.getMessage());
            throw fetchFailure("Exchange-rate unexpected failure: " + e.getMessage());
        }
    }

    private BigDecimal parseIdrRate(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw fetchFailure("Exchange-rate empty response");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!"success".equals(root.path("result").asText())) {
                throw fetchFailure("Exchange-rate response result was not success");
            }
            JsonNode idr = root.path("rates").path("IDR");
            if (idr.isMissingNode() || !idr.isNumber()) {
                throw fetchFailure("Exchange-rate response missing IDR rate");
            }
            BigDecimal rate = idr.decimalValue();
            if (rate.signum() <= 0) {
                throw fetchFailure("Exchange-rate IDR value was not positive");
            }
            return rate;
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw fetchFailure("Exchange-rate response parse error: " + e.getMessage());
        }
    }

    private DomainException fetchFailure(String message) {
        return new DomainException(
                ErrorCodes.EXCHANGE_RATE_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE,
                message);
    }
}
