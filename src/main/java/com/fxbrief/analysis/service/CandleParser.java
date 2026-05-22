package com.fxbrief.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.dto.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses a Twelve Data {@code /time_series} per-symbol payload into an
 * oldest-first list of {@link Candle}. The API returns values newest-first;
 * we reverse so positional indexing matches chronological time.
 */
@Slf4j
@Component
public class CandleParser {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper objectMapper;

    public CandleParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Candle> parse(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            JsonNode values = root.path("values");
            if (!values.isArray()) {
                return List.of();
            }
            List<Candle> candles = new ArrayList<>(values.size());
            for (JsonNode node : values) {
                Candle candle = mapCandle(node);
                if (candle != null) {
                    candles.add(candle);
                }
            }
            Collections.reverse(candles);
            return candles;
        } catch (Exception e) {
            log.warn("Failed to parse candle payload: {}", e.getMessage());
            return List.of();
        }
    }

    private Candle mapCandle(JsonNode node) {
        try {
            Instant timestamp = parseTimestamp(node.path("datetime").asText(null));
            if (timestamp == null) {
                return null;
            }
            double open = parseDouble(node, "open");
            double high = parseDouble(node, "high");
            double low = parseDouble(node, "low");
            double close = parseDouble(node, "close");
            double volume = node.has("volume") ? parseDouble(node, "volume") : 0.0;
            return new Candle(timestamp, open, high, low, close, volume);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0.0;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        String text = value.asText();
        if (text.isBlank()) {
            return 0.0;
        }
        return Double.parseDouble(text);
    }

    private Instant parseTimestamp(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(text, DATE_ONLY).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
