package com.fxbrief.analysis.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.config.ForexFactoryProperties;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Fetches the weekly economic calendar from ForexFactory's public CDN.
 *
 * No API key is required — the URL is publicly served. The file covers the
 * current ISO week (Mon-Sun in UTC) and contains a flat JSON array of events
 * with fields {@code title}, {@code country}, {@code date}, {@code impact},
 * {@code forecast}, {@code previous}, and (when populated) {@code actual}.
 * The shape is well-known and stable since the 2020 schema migration.
 *
 * ForexFactory enforces a rate limit of 2 downloads per 5 minutes across all
 * file formats (xml/json/ics/csv) — see DECISIONS D-043. The scheduler runs
 * once daily with a startup-hook fallback that skips if the cache is less
 * than 12h old, keeping us well under the limit even across rapid restarts.
 *
 * A polite User-Agent identifies the caller so ForexFactory can contact us if
 * they ever need to (and so the request isn't treated as anonymous bot
 * traffic by their CDN).
 *
 * Resilience4j {@code forexFactory} instance wraps retry + circuit breaker.
 */
@Slf4j
@Component
public class ForexFactoryCalendarClient {

    public static final String RESILIENCE_NAME = "forexFactory";

    private static final String CALENDAR_PATH = "/ff_calendar_thisweek.json";

    private final ForexFactoryProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ForexFactoryCalendarClient(ForexFactoryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(buildRequestFactory(properties))
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(ForexFactoryProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return factory;
    }

    /**
     * Fetches the current-week calendar. Returns the parsed JsonNode (a flat
     * array). Throws {@link DomainException} with code
     * {@code MARKET_DATA_UNAVAILABLE} on any HTTP or parse failure — the same
     * code path as Twelve Data so existing handling logic applies.
     */
    @Retry(name = RESILIENCE_NAME)
    @CircuitBreaker(name = RESILIENCE_NAME)
    public JsonNode fetchCalendar() {
        try {
            String body = restClient.get()
                    .uri(CALENDAR_PATH)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw apiFailure("ForexFactory returned empty body");
            }
            if (body.trim().startsWith("<")) {
                throw apiFailure("ForexFactory returned HTML (rate-limited or denied)");
            }
            JsonNode parsed = objectMapper.readTree(body);
            if (!parsed.isArray()) {
                throw apiFailure("ForexFactory response was not a JSON array");
            }
            return parsed;
        } catch (HttpClientErrorException e) {
            log.warn("ForexFactory client error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw apiFailure("ForexFactory client error " + e.getStatusCode());
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("ForexFactory transient failure: {}", e.getMessage());
            throw apiFailure("ForexFactory transient failure: " + e.getMessage());
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ForexFactory unexpected failure: {}", e.getMessage());
            throw apiFailure("ForexFactory unexpected failure: " + e.getMessage());
        }
    }

    private DomainException apiFailure(String message) {
        return new DomainException(
                ErrorCodes.MARKET_DATA_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE,
                message);
    }
}
