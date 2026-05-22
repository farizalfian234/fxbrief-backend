package com.fxbrief.analysis.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.config.TwelveDataProperties;
import com.fxbrief.analysis.entity.Pair;
import com.fxbrief.analysis.entity.Timeframe;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Thin client for the Twelve Data REST API, used for OHLCV only.
 *
 * One method, one HTTP request: a batch /time_series call returning all 8
 * pairs for a single timeframe. Each batch consumes 8 Twelve Data credits
 * (1 weight × 8 symbols), which equals the entire free-tier per-minute budget,
 * so callers MUST pace successive batches at least 60s apart. The
 * MarketDataPrefetchService is the canonical caller and enforces that pacing.
 *
 * 429 responses are honored by extracting the Retry-After header and exposing
 * the wait duration through a typed {@link RateLimitedException}, which the
 * caller can use to delay the next batch rather than blindly retrying via
 * Resilience4j's exponential backoff. All other failures are translated into a
 * {@link DomainException} with {@code MARKET_DATA_UNAVAILABLE} so the
 * Resilience4j retry policy can fire.
 *
 * The economic calendar fetch lives elsewhere now ({@code ForexFactoryCalendarClient}).
 */
@Slf4j
@Component
public class TwelveDataClient {

    public static final String RESILIENCE_NAME = "twelveData";

    private static final int DEFAULT_RATE_LIMIT_BACKOFF_SECONDS = 60;

    private final TwelveDataProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TwelveDataClient(TwelveDataProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(buildRequestFactory(properties))
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(TwelveDataProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return factory;
    }

    /**
     * Fetches OHLCV for every supported pair at the given timeframe in a
     * single batch HTTP call. Returns one JSON payload per pair, keyed by pair
     * symbol (e.g. {@code EUR/USD}). When Twelve Data returns a single-symbol
     * response (one pair only) it is normalised into the same multi-symbol
     * shape.
     */
    @Retry(name = RESILIENCE_NAME)
    @CircuitBreaker(name = RESILIENCE_NAME)
    public Map<String, JsonNode> fetchOhlcvBatch(Timeframe timeframe) {
        String symbols = Arrays.stream(Pair.values())
                .map(Pair::getSymbol)
                .collect(Collectors.joining(","));

        String uri = UriComponentsBuilder.fromPath("/time_series")
                .queryParam("symbol", symbols)
                .queryParam("interval", timeframe.getTwelveDataInterval())
                .queryParam("outputsize", properties.outputSize())
                .queryParam("format", "JSON")
                .queryParam("apikey", properties.apiKey())
                .build(false)
                .toUriString();

        JsonNode response = exchange(uri, "OHLCV batch interval=" + timeframe.getTwelveDataInterval());

        return splitBatchResponse(response);
    }

    /**
     * Twelve Data returns either {@code {"EUR/USD": {...}, "GBP/USD": {...}}}
     * (multi-symbol) or the symbol body directly (single-symbol fallback).
     * We normalise to the multi-symbol shape so the caller never has to
     * branch. A per-symbol error object with {@code status=error} is logged
     * and skipped; the absent key is reported to the scheduler so it can
     * leave the previous cached payload intact for that pair.
     */
    private Map<String, JsonNode> splitBatchResponse(JsonNode response) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (response == null || !response.isObject()) {
            throw apiFailure("Twelve Data batch response was not a JSON object");
        }

        if (response.has("status") && "error".equalsIgnoreCase(response.path("status").asText())) {
            throw apiFailure("Twelve Data batch error: " + response.path("message").asText("unknown"));
        }

        if (response.has("values") || response.has("meta")) {
            String inferred = response.path("meta").path("symbol").asText(null);
            if (inferred != null) {
                result.put(inferred, response);
            }
            return result;
        }

        response.fieldNames().forEachRemaining(symbol -> {
            JsonNode body = response.get(symbol);
            if (body == null) {
                return;
            }
            if (body.has("status") && "error".equalsIgnoreCase(body.path("status").asText())) {
                log.warn("Twelve Data returned error for symbol {}: {}",
                        symbol, body.path("message").asText("unknown"));
                return;
            }
            result.put(symbol, body);
        });
        return result;
    }

    private JsonNode exchange(String uri, String context) {
        try {
            String body = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw apiFailure("Twelve Data empty response for " + context);
            }
            return objectMapper.readTree(body);
        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                int retryAfter = parseRetryAfterSeconds(e.getResponseHeaders());
                log.warn("Twelve Data rate-limit hit for {}; retry-after {}s",
                        context, retryAfter);
                throw new RateLimitedException(retryAfter,
                        "Twelve Data 429 rate-limited for " + context);
            }
            log.warn("Twelve Data client error for {}: status={} body={}",
                    context, status, e.getResponseBodyAsString());
            throw apiFailure("Twelve Data client error " + status);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("Twelve Data transient failure for {}: {}", context, e.getMessage());
            throw apiFailure("Twelve Data transient failure: " + e.getMessage());
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Twelve Data unexpected failure for {}: {}", context, e.getMessage());
            throw apiFailure("Twelve Data unexpected failure: " + e.getMessage());
        }
    }

    private int parseRetryAfterSeconds(HttpHeaders headers) {
        if (headers == null) {
            return DEFAULT_RATE_LIMIT_BACKOFF_SECONDS;
        }
        List<String> values = headers.get(HttpHeaders.RETRY_AFTER);
        if (values == null || values.isEmpty()) {
            return DEFAULT_RATE_LIMIT_BACKOFF_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(values.get(0).trim());
            return Math.max(parsed, 1);
        } catch (NumberFormatException e) {
            return DEFAULT_RATE_LIMIT_BACKOFF_SECONDS;
        }
    }

    private DomainException apiFailure(String message) {
        return new DomainException(
                ErrorCodes.MARKET_DATA_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE,
                message);
    }

    /**
     * Thrown when Twelve Data returns 429. Extends {@link DomainException} so
     * Resilience4j's retry policy (which retries on DomainException) still
     * fires, but the caller can also pattern-match this specific subtype to
     * apply a longer-than-default wait before the next attempt.
     */
    public static final class RateLimitedException extends DomainException {

        private final int retryAfterSeconds;

        public RateLimitedException(int retryAfterSeconds, String message) {
            super(ErrorCodes.MARKET_DATA_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
