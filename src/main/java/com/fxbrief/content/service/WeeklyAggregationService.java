package com.fxbrief.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.analysis.repository.MarketAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the public-safe structured input for the weekly recap from stored
 * {@code market_analysis} rows.
 *
 * <p>The analysis table is keyed by fetch cycle and has no forex-market-date
 * column; multiple cycles land per day during market hours. Each row is mapped
 * to its forex market date by shifting {@code market_data_fetched_at} forward
 * two hours so the 22:00 UTC forex-day boundary (DECISIONS D-030) lands on
 * midnight, then taking the UTC date. The latest cycle per forex day is the
 * representative snapshot for that day, matching what users saw at the close.
 *
 * <p>Only the public-facing signals PRD §9.1.3 permits are extracted — most
 * active pairs, dominant bias direction, macro drivers, structure shifts, and
 * strongest/weakest pairs by confidence <em>level</em>. Entry/SL/TP and exact
 * confidence scores are never read into the aggregate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyAggregationService {

    private static final int FOREX_DAY_SHIFT_HOURS = 24 - 22;

    private final MarketAnalysisRepository marketAnalysisRepository;
    private final ObjectMapper objectMapper;

    /**
     * Aggregates the analysis rows whose forex market date falls within
     * {@code [weekStart, weekEnd]} into a public-safe structured text block.
     * Returns {@code null} when no analysis data exists for the window — the
     * caller logs and skips rather than generating an empty recap.
     */
    @Transactional(readOnly = true)
    public String buildAggregate(LocalDate weekStart, LocalDate weekEnd) {
        Instant from = forexDateStart(weekStart);
        Instant to = forexDateStart(weekEnd.plusDays(1));

        List<MarketAnalysis> rows = marketAnalysisRepository
                .findByMarketDataFetchedAtBetweenOrderByMarketDataFetchedAtAsc(from, to);
        if (rows.isEmpty()) {
            return null;
        }

        Map<LocalDate, MarketAnalysis> latestPerDay = new TreeMap<>();
        for (MarketAnalysis row : rows) {
            LocalDate forexDate = forexMarketDateOf(row.getMarketDataFetchedAt());
            if (forexDate.isBefore(weekStart) || forexDate.isAfter(weekEnd)) {
                continue;
            }
            latestPerDay.put(forexDate, row);
        }
        if (latestPerDay.isEmpty()) {
            return null;
        }

        return render(latestPerDay);
    }

    private String render(Map<LocalDate, MarketAnalysis> latestPerDay) {
        StringBuilder out = new StringBuilder();
        out.append("Forex market days covered: ").append(latestPerDay.size()).append('\n');

        Map<String, Integer> activeBySetup = new LinkedHashMap<>();
        Map<String, Integer> biasTally = new LinkedHashMap<>();
        List<String> highImpactDrivers = new ArrayList<>();

        for (Map.Entry<LocalDate, MarketAnalysis> entry : latestPerDay.entrySet()) {
            LocalDate day = entry.getKey();
            JsonNode payload = readPayload(entry.getValue());
            if (payload == null) {
                continue;
            }

            out.append("\n=== ").append(day).append(" ===\n");
            boolean consolidating = payload.path("marketsConsolidating").asBoolean(false);
            out.append("Markets consolidating: ").append(consolidating).append('\n');

            JsonNode pairs = payload.path("pairs");
            if (!pairs.isArray()) {
                continue;
            }

            List<String> strongest = new ArrayList<>();
            List<String> weakest = new ArrayList<>();
            for (JsonNode pair : pairs) {
                String symbol = pair.path("pair").asText("");
                String dailyBias = biasFor(pair);
                String confidenceLevel = pair.path("confidence").path("level").asText("");
                String signalState = pair.path("signalState").asText("");

                if (!symbol.isEmpty()) {
                    biasTally.merge(dailyBias, 1, Integer::sum);
                    if (isActiveSetup(signalState)) {
                        activeBySetup.merge(symbol, 1, Integer::sum);
                    }
                }
                if ("HIGH".equals(confidenceLevel)) {
                    strongest.add(symbol);
                } else if ("LOW".equals(confidenceLevel)) {
                    weakest.add(symbol);
                }
                collectMacroDrivers(pair, day, highImpactDrivers);
            }

            out.append("Strongest pairs (high confidence): ")
                    .append(strongest.isEmpty() ? "none" : String.join(", ", strongest)).append('\n');
            out.append("Weakest pairs (low confidence): ")
                    .append(weakest.isEmpty() ? "none" : String.join(", ", weakest)).append('\n');
        }

        out.append("\n=== Week aggregate ===\n");
        out.append("Most active pairs (by days with an active setup): ")
                .append(formatTopCounts(activeBySetup)).append('\n');
        out.append("Dominant bias direction across the week: ")
                .append(dominantBias(biasTally)).append('\n');
        out.append("Bias distribution: ").append(formatCounts(biasTally)).append('\n');
        out.append("Major macro drivers flagged this week:\n");
        if (highImpactDrivers.isEmpty()) {
            out.append("  none flagged\n");
        } else {
            highImpactDrivers.stream().distinct().forEach(d -> out.append("  - ").append(d).append('\n'));
        }
        return out.toString();
    }

    private JsonNode readPayload(MarketAnalysis row) {
        try {
            return objectMapper.readTree(row.getPayload());
        } catch (Exception e) {
            log.warn("Skipping unreadable market_analysis payload id={}: {}", row.getId(), e.getMessage());
            return null;
        }
    }

    private String biasFor(JsonNode pair) {
        JsonNode daily = pair.path("structureByTimeframe").path("D").path("bias");
        if (daily.isMissingNode() || daily.asText().isEmpty()) {
            return "UNKNOWN";
        }
        return daily.asText();
    }

    private boolean isActiveSetup(String signalState) {
        return "CONFIRMED".equals(signalState) || "AWAITING_CONFIRMATION".equals(signalState);
    }

    private void collectMacroDrivers(JsonNode pair, LocalDate day, List<String> drivers) {
        JsonNode fundamental = pair.path("fundamental");
        if (!fundamental.path("highImpactThisWeek").asBoolean(false)) {
            return;
        }
        JsonNode events = fundamental.path("events");
        if (!events.isArray()) {
            return;
        }
        for (JsonNode event : events) {
            String title = event.path("title").asText("");
            if (title.isEmpty()) {
                continue;
            }
            String currency = event.path("currency").asText("");
            if (isHighImportance(event)) {
                drivers.add("%s %s (%s)".formatted(currency, title, day).trim());
            }
        }
    }

    private boolean isHighImportance(JsonNode event) {
        String impact = event.path("impact").asText("");
        String importance = event.path("importance").asText("");
        return "HIGH".equalsIgnoreCase(impact) || "HIGH".equalsIgnoreCase(importance);
    }

    private String formatTopCounts(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return "none";
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> "%s (%d)".formatted(e.getKey(), e.getValue()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    private String formatCounts(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return "none";
        }
        return counts.entrySet().stream()
                .map(e -> "%s=%d".formatted(e.getKey(), e.getValue()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    private String dominantBias(Map<String, Integer> biasTally) {
        return biasTally.entrySet().stream()
                .filter(e -> !"UNKNOWN".equals(e.getKey()))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("mixed");
    }

    private Instant forexDateStart(LocalDate forexDate) {
        return forexDate.minusDays(1).atTime(22, 0).toInstant(ZoneOffset.UTC);
    }

    private LocalDate forexMarketDateOf(Instant instant) {
        return instant.plusSeconds(FOREX_DAY_SHIFT_HOURS * 3600L)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
    }
}
