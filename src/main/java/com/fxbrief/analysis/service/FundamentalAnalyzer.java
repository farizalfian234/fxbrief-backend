package com.fxbrief.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.EconomicEvent;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.FundamentalAssessment.FundamentalBias;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the cached economic calendar JSON into per-currency
 * {@link FundamentalAssessment} records.
 *
 * The source is ForexFactory's {@code ff_calendar_thisweek.json}, a flat array
 * of events. Each event carries {@code country} (3-letter currency code),
 * {@code title}, {@code date} (ISO 8601), {@code impact} ("Low"/"Medium"/
 * "High"/"Holiday"), and optionally {@code actual}, {@code forecast},
 * {@code previous}. We group events by {@code country} and run the per-
 * currency logic that scores bias and flags high-impact weeks.
 *
 * Bias inference is intentionally conservative: only fires when an event has
 * BOTH {@code actual} AND {@code forecast} numeric values. Forward-looking
 * events without an {@code actual} still flip the {@code highImpactThisWeek}
 * flag (which drives the -3 confidence-conflict penalty per PRD §7.10) but do
 * not lean directional. This avoids false-precision bias on pure expectation
 * signals.
 */
@Slf4j
@Component
public class FundamentalAnalyzer {

    private static final DateTimeFormatter TWELVE_DATA_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TWELVE_DATA_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public FundamentalAnalyzer(AnalysisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, FundamentalAssessment> analyze(String calendarPayloadJson) {
        if (calendarPayloadJson == null || calendarPayloadJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(calendarPayloadJson);
            Map<String, List<JsonNode>> eventsByCurrency = groupByCurrency(root);
            Map<String, FundamentalAssessment> result = new HashMap<>();
            eventsByCurrency.forEach((currency, nodes) -> {
                FundamentalAssessment assessment = analyzeCurrency(currency, nodes);
                if (assessment != null) {
                    result.put(currency, assessment);
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse economic calendar payload: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Accepts both shapes:
     * <ul>
     *   <li>Flat array of events (ForexFactory) — group by {@code country}/{@code currency} field.</li>
     *   <li>Object keyed by currency with a nested events array (legacy) — pass through.</li>
     * </ul>
     */
    private Map<String, List<JsonNode>> groupByCurrency(JsonNode root) {
        Map<String, List<JsonNode>> grouped = new HashMap<>();
        if (root.isArray()) {
            for (JsonNode event : root) {
                String currency = textOrNull(event, "country", "currency", "countryCode");
                if (currency == null) {
                    continue;
                }
                grouped.computeIfAbsent(currency.toUpperCase(), k -> new ArrayList<>()).add(event);
            }
            return grouped;
        }
        if (root.isObject()) {
            root.fieldNames().forEachRemaining(currency -> {
                JsonNode body = root.get(currency);
                JsonNode events = pickEventsNode(body);
                if (events != null && events.isArray()) {
                    List<JsonNode> list = new ArrayList<>();
                    events.forEach(list::add);
                    grouped.put(currency.toUpperCase(), list);
                }
            });
        }
        return grouped;
    }

    private FundamentalAssessment analyzeCurrency(String currency, List<JsonNode> eventNodes) {
        if (eventNodes == null || eventNodes.isEmpty()) {
            return new FundamentalAssessment(currency, FundamentalBias.NEUTRAL, false, List.of());
        }

        String highImpactThreshold = properties.fundamental().highImpactLevel();
        List<EconomicEvent> events = new ArrayList<>();
        boolean highImpactThisWeek = false;
        int bullishSignals = 0;
        int bearishSignals = 0;

        for (JsonNode node : eventNodes) {
            EconomicEvent event = mapEvent(currency, node);
            if (event == null) {
                continue;
            }
            events.add(event);

            if (isHighImpact(event.importance(), highImpactThreshold)) {
                highImpactThisWeek = true;
            }

            BiasContribution contribution = inferBias(event);
            if (contribution == BiasContribution.BULLISH) {
                bullishSignals++;
            } else if (contribution == BiasContribution.BEARISH) {
                bearishSignals++;
            }
        }

        FundamentalBias bias;
        if (bullishSignals > bearishSignals) {
            bias = FundamentalBias.BULLISH;
        } else if (bearishSignals > bullishSignals) {
            bias = FundamentalBias.BEARISH;
        } else {
            bias = FundamentalBias.NEUTRAL;
        }

        return new FundamentalAssessment(currency, bias, highImpactThisWeek, events);
    }

    private JsonNode pickEventsNode(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        if (body.isArray()) {
            return body;
        }
        for (String field : List.of("data", "events", "values", "calendar")) {
            if (body.has(field) && body.get(field).isArray()) {
                return body.get(field);
            }
        }
        return null;
    }

    private EconomicEvent mapEvent(String currency, JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String name = textOrNull(node, "title", "event", "name");
        if (name == null) {
            return null;
        }
        Instant date = parseDate(textOrNull(node, "date", "datetime", "time"));
        String importance = textOrNull(node, "impact", "importance", "priority");
        String actual = textOrNull(node, "actual");
        String forecast = textOrNull(node, "forecast", "estimate", "consensus");
        String previous = textOrNull(node, "previous", "prior");
        return new EconomicEvent(currency, name, date, importance, actual, forecast, previous);
    }

    private String textOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private Instant parseDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text, TWELVE_DATA_DATETIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(text, TWELVE_DATA_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * ForexFactory uses "Low"/"Medium"/"High"/"Holiday"; Twelve Data used
     * "Low"/"Medium"/"High" or 1/2/3. Map all of them into a 0-3 rank and
     * compare against the configured threshold.
     */
    private boolean isHighImpact(String importance, String threshold) {
        if (importance == null || threshold == null) {
            return false;
        }
        int rank = importanceRank(importance);
        int thresholdRank = importanceRank(threshold);
        return rank > 0 && thresholdRank > 0 && rank >= thresholdRank;
    }

    private int importanceRank(String value) {
        String v = value.trim().toUpperCase();
        return switch (v) {
            case "LOW", "1" -> 1;
            case "MEDIUM", "MED", "2" -> 2;
            case "HIGH", "3" -> 3;
            default -> 0;
        };
    }

    private BiasContribution inferBias(EconomicEvent event) {
        if (event.actual() == null || event.forecast() == null) {
            return BiasContribution.NEUTRAL;
        }
        Double actual = parseNumber(event.actual());
        Double forecast = parseNumber(event.forecast());
        if (actual == null || forecast == null) {
            return BiasContribution.NEUTRAL;
        }
        if (actual > forecast) {
            return BiasContribution.BULLISH;
        }
        if (actual < forecast) {
            return BiasContribution.BEARISH;
        }
        return BiasContribution.NEUTRAL;
    }

    private Double parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.replaceAll("[^0-9.\\-]", "");
        if (stripped.isEmpty() || stripped.equals("-") || stripped.equals(".")) {
            return null;
        }
        try {
            return Double.parseDouble(stripped);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private enum BiasContribution { BULLISH, BEARISH, NEUTRAL }
}
