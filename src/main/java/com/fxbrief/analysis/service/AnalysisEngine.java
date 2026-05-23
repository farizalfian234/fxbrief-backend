package com.fxbrief.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.PairAnalysis;
import com.fxbrief.analysis.dto.PairNarrativeFields;
import com.fxbrief.analysis.dto.ReportPayload;
import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.analysis.entity.Pair;
import com.fxbrief.analysis.repository.MarketAnalysisRepository;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.service.ReportSummaryComposer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Top-level analysis orchestrator.
 *
 * <h2>Shared analysis per fetch cycle (DECISIONS D-044)</h2>
 * Claude is called at most once per fetch cycle, not once per user request.
 * The flow:
 * <ol>
 *   <li>Resolve the current {@code fetch_id} from the cache.</li>
 *   <li>If a {@code market_analysis} row already exists for that id, return
 *       its stored {@link ReportPayload} immediately — no Claude call.</li>
 *   <li>On miss: run SMC for all 8 pairs, generate the five Claude text
 *       fields per pair plus a report-level summary via the mega-call (with
 *       per-pair fallback for invalid pairs and optional shadow logging),
 *       persist to {@code market_analysis}, return the row.</li>
 * </ol>
 *
 * <h2>Plan-agnostic generation (DECISIONS D-042)</h2>
 * The engine always produces full Premium-depth content. The user's
 * subscription plan is irrelevant at generation time — the frontend
 * filters at render time.
 *
 * <h2>Concurrency</h2>
 * Two concurrent users requesting at the same instant might both miss
 * the cache. Both will run the full pipeline; the second to call
 * {@code MarketAnalysisWriter.saveOrReturnExisting} catches the unique-
 * constraint violation and re-reads the row written by the first. The
 * loser's Claude call is wasted work — about $0.01 — but the data is
 * consistent.
 *
 * This method does not persist {@code user_reports} and does not consume
 * a report credit — those concerns live in {@code report/} (Phase 3B).
 */
@Slf4j
@Service
public class AnalysisEngine {

    private static final int NARRATIVE_POOL_SIZE = Pair.values().length;
    private static final long NARRATIVE_TIMEOUT_SECONDS = 90;

    static final String NARRATIVE_MODE_MEGA = "MEGA";
    static final String NARRATIVE_MODE_HYBRID = "HYBRID";
    static final String NARRATIVE_MODE_PER_PAIR_FALLBACK = "PER_PAIR_FALLBACK";

    private final MarketDataReader marketDataReader;
    private final FundamentalAnalyzer fundamentalAnalyzer;
    private final PairAnalysisEngine pairAnalysisEngine;
    private final BestPairSelector bestPairSelector;
    private final ClaudeNarrativeGenerator perPairNarrativeGenerator;
    private final MegaCallNarrativeService megaCallNarrativeService;
    private final MarketAnalysisRepository marketAnalysisRepository;
    private final MarketAnalysisWriter marketAnalysisWriter;
    private final ReportSummaryComposer summaryComposer;
    private final ObjectMapper objectMapper;
    private final AnalysisProperties analysisProperties;
    private final ExecutorService narrativePool;

    public AnalysisEngine(MarketDataReader marketDataReader,
                          FundamentalAnalyzer fundamentalAnalyzer,
                          PairAnalysisEngine pairAnalysisEngine,
                          BestPairSelector bestPairSelector,
                          ClaudeNarrativeGenerator perPairNarrativeGenerator,
                          MegaCallNarrativeService megaCallNarrativeService,
                          MarketAnalysisRepository marketAnalysisRepository,
                          MarketAnalysisWriter marketAnalysisWriter,
                          ReportSummaryComposer summaryComposer,
                          ObjectMapper objectMapper,
                          AnalysisProperties analysisProperties) {
        this.marketDataReader = marketDataReader;
        this.fundamentalAnalyzer = fundamentalAnalyzer;
        this.pairAnalysisEngine = pairAnalysisEngine;
        this.bestPairSelector = bestPairSelector;
        this.perPairNarrativeGenerator = perPairNarrativeGenerator;
        this.megaCallNarrativeService = megaCallNarrativeService;
        this.marketAnalysisRepository = marketAnalysisRepository;
        this.marketAnalysisWriter = marketAnalysisWriter;
        this.summaryComposer = summaryComposer;
        this.objectMapper = objectMapper;
        this.analysisProperties = analysisProperties;
        this.narrativePool = Executors.newFixedThreadPool(NARRATIVE_POOL_SIZE, runnable -> {
            Thread t = new Thread(runnable, "fxbrief-narrative");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns the shared analysis row for the current fetch cycle alongside its
     * deserialised payload. Reuses an existing {@code market_analysis} row when
     * one exists; otherwise runs the full pipeline and persists.
     *
     * The row is returned so callers can foreign-key against {@code id} and copy
     * {@code summary} into per-user storage without a second SELECT.
     */
    public AnalysisResult runAnalysis() {
        UUID fetchId = marketDataReader.resolveCurrentFetchId()
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.MARKET_DATA_NOT_READY,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "No complete pre-fetch cycle available yet"));

        Optional<MarketAnalysis> existing = marketAnalysisRepository.findByFetchId(fetchId);
        if (existing.isPresent()) {
            log.info("Reusing existing market_analysis for fetch_id={}", fetchId);
            MarketAnalysis row = existing.get();
            return new AnalysisResult(row, deserialise(row.getPayload()));
        }

        log.info("No market_analysis for fetch_id={} — running full pipeline", fetchId);
        return computeAndPersist(fetchId);
    }

    private AnalysisResult computeAndPersist(UUID fetchId) {
        Instant now = Instant.now();
        Optional<MarketDataReader.CalendarSnapshot> calendar = marketDataReader.readCalendar();
        Map<String, FundamentalAssessment> fundamentals = calendar
                .map(snapshot -> fundamentalAnalyzer.analyze(snapshot.payload()))
                .orElseGet(Map::of);
        Instant calendarFetchedAt = calendar.map(MarketDataReader.CalendarSnapshot::fetchedAt).orElse(null);

        List<PairAnalysis> analyses = new ArrayList<>();
        Instant latestMarketFetch = null;
        for (Pair pair : Pair.values()) {
            try {
                MarketDataReader.PairMarketData md = marketDataReader.readPair(pair);
                PairAnalysis analysis = pairAnalysisEngine.analyze(md, fundamentals, now);
                analyses.add(analysis);
                if (latestMarketFetch == null || md.fetchedAt().isAfter(latestMarketFetch)) {
                    latestMarketFetch = md.fetchedAt();
                }
            } catch (DomainException e) {
                if (!ErrorCodes.MARKET_DATA_NOT_READY.equals(e.getCode())) {
                    throw e;
                }
                log.warn("Skipping {} — cached market data not ready: {}", pair.getSymbol(), e.getMessage());
            }
        }

        if (analyses.isEmpty()) {
            throw new DomainException(
                    ErrorCodes.MARKET_DATA_NOT_READY,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Market data cache is empty — pre-fetch has not run yet");
        }

        NarrativeOutcome outcome = generateNarratives(analyses, fetchId);
        Optional<String> bestPair = bestPairSelector.select(outcome.analyses());
        boolean marketsConsolidating = bestPair.isEmpty()
                && bestPairSelector.allMarketsConsolidating(outcome.analyses());
        List<PairAnalysis> ordered = orderBestFirst(outcome.analyses(), bestPair.orElse(null));

        ReportPayload payload = new ReportPayload(
                bestPair.orElse(null),
                ordered,
                marketsConsolidating,
                now,
                latestMarketFetch,
                calendarFetchedAt);

        // Summary source priority:
        //   1. Mega-call result (Claude per PRD §8.4)
        //   2. Java composer fallback (DECISIONS D-051)
        String summary = outcome.megaSummary();
        if (summary == null || summary.isBlank()) {
            summary = summaryComposer.compose(payload);
            log.info("Using Java-composed summary fallback for fetch_id={}", fetchId);
        }

        MarketAnalysis row = persistAnalysis(fetchId, payload, summary, outcome.mode(),
                outcome.invalidCount(), latestMarketFetch, calendarFetchedAt);

        return new AnalysisResult(row, payload);
    }

    /**
     * Drives narrative generation per DECISIONS D-045:
     * <ol>
     *   <li>Attempt the mega-call. Capture per-pair fields plus the
     *       report-level summary.</li>
     *   <li>For any pair that failed mega-call validation (or all pairs if
     *       the mega-call threw), fall back to a per-pair call.</li>
     *   <li>If shadow logging is enabled, also run per-pair calls for
     *       every successfully-mega-validated pair and write the QA log.</li>
     * </ol>
     */
    private NarrativeOutcome generateNarratives(List<PairAnalysis> analyses, UUID fetchId) {
        Map<String, PairNarrativeFields> megaFields;
        String megaSummary;
        boolean megaFailed = false;
        try {
            MegaCallNarrativeService.MegaCallResult megaResult =
                    megaCallNarrativeService.generateAll(analyses);
            megaFields = megaResult.perPair();
            megaSummary = megaResult.summary();
        } catch (DomainException e) {
            log.warn("Mega-call failed entirely; falling back to per-pair for all pairs: {}", e.getMessage());
            megaFields = Map.of();
            megaSummary = null;
            megaFailed = true;
        }

        Map<String, PairNarrativeFields> finalFields = new LinkedHashMap<>(megaFields);
        List<PairAnalysis> needFallback = new ArrayList<>();
        for (PairAnalysis a : analyses) {
            if (!finalFields.containsKey(a.pair())) {
                needFallback.add(a);
            }
        }

        if (!needFallback.isEmpty()) {
            log.info("Per-pair fallback required for {} pair(s): {}",
                    needFallback.size(),
                    needFallback.stream().map(PairAnalysis::pair).toList());
            Map<String, PairNarrativeFields> fallbackFields = generatePerPairParallel(needFallback);
            finalFields.putAll(fallbackFields);
        }

        if (analysisProperties.shadowNarrativeLogging() != null
                && analysisProperties.shadowNarrativeLogging().enabled()
                && !megaFailed) {
            runShadowComparison(analyses, megaFields, fetchId);
        }

        List<PairAnalysis> withFields = new ArrayList<>(analyses.size());
        for (PairAnalysis a : analyses) {
            PairNarrativeFields fields = finalFields.getOrDefault(a.pair(),
                    PairNarrativeFields.fallback());
            withFields.add(withClaudeFields(a, fields));
        }

        String mode;
        if (megaFailed) {
            mode = NARRATIVE_MODE_PER_PAIR_FALLBACK;
        } else if (needFallback.isEmpty()) {
            mode = NARRATIVE_MODE_MEGA;
        } else {
            mode = NARRATIVE_MODE_HYBRID;
        }
        return new NarrativeOutcome(withFields, mode, (short) needFallback.size(), megaSummary);
    }

    private Map<String, PairNarrativeFields> generatePerPairParallel(List<PairAnalysis> pairs) {
        Map<String, CompletableFuture<PairNarrativeFields>> futures = new LinkedHashMap<>();
        for (PairAnalysis a : pairs) {
            futures.put(a.pair(), CompletableFuture.supplyAsync(
                    () -> perPairNarrativeGenerator.generate(a), narrativePool));
        }

        try {
            CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
                    .get(NARRATIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Per-pair narrative batch did not complete within {}s: {}",
                    NARRATIVE_TIMEOUT_SECONDS, e.getMessage());
        }

        Map<String, PairNarrativeFields> result = new LinkedHashMap<>();
        futures.forEach((pair, future) -> {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    result.put(pair, future.getNow(PairNarrativeFields.fallback()));
                } catch (Exception e) {
                    result.put(pair, PairNarrativeFields.fallback());
                }
            } else {
                future.cancel(true);
                result.put(pair, PairNarrativeFields.fallback());
            }
        });
        return result;
    }

    private void runShadowComparison(List<PairAnalysis> analyses,
                                     Map<String, PairNarrativeFields> megaFields,
                                     UUID fetchId) {
        List<PairAnalysis> toShadow = analyses.stream()
                .filter(a -> megaFields.containsKey(a.pair()))
                .toList();
        if (toShadow.isEmpty()) {
            return;
        }
        Map<String, PairNarrativeFields> perPair = generatePerPairParallel(toShadow);
        for (PairAnalysis a : toShadow) {
            PairNarrativeFields mega = megaFields.get(a.pair());
            PairNarrativeFields solo = perPair.getOrDefault(a.pair(), PairNarrativeFields.fallback());
            boolean diverged = isDivergent(mega, solo);
            String reason = diverged ? "length or content drift" : null;
            // Concatenate the five fields per side for the shadow log — keeps
            // the existing narrative_qa_log schema usable without migration.
            String megaJoined = joinFields(mega);
            String soloJoined = joinFields(solo);
            try {
                marketAnalysisWriter.logShadow(fetchId, a.pair(), megaJoined, soloJoined, diverged, reason);
            } catch (Exception e) {
                log.warn("Shadow log write failed for {}: {}", a.pair(), e.getMessage());
            }
        }
    }

    private String joinFields(PairNarrativeFields f) {
        if (f == null) return "";
        return "[setupStatus] " + nullSafe(f.setupStatus())
                + "\n[shortReasoning] " + nullSafe(f.shortReasoning())
                + "\n[executiveReasoning] " + nullSafe(f.executiveReasoning())
                + "\n[invalidationNote] " + nullSafe(f.invalidationNote())
                + "\n[fundamentalSummary] " + nullSafe(f.fundamentalSummary());
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private boolean isDivergent(PairNarrativeFields mega, PairNarrativeFields solo) {
        if (mega == null || solo == null) {
            return true;
        }
        int megaLen = joinFields(mega).length();
        int soloLen = joinFields(solo).length();
        if (megaLen == 0 || soloLen == 0) {
            return true;
        }
        double ratio = (double) Math.min(megaLen, soloLen) / Math.max(megaLen, soloLen);
        return ratio < 0.5;
    }

    private MarketAnalysis persistAnalysis(UUID fetchId, ReportPayload payload, String summary,
                                           String mode, short invalidCount,
                                           Instant marketDataFetchedAt, Instant calendarFetchedAt) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MarketAnalysis saved = marketAnalysisWriter.saveOrReturnExisting(
                    fetchId, json, summary, mode, invalidCount, marketDataFetchedAt, calendarFetchedAt);
            log.info("Persisted market_analysis fetch_id={} mode={} invalidPairs={}",
                    fetchId, mode, invalidCount);
            return saved;
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise ReportPayload for fetch_id {}: {}", fetchId, e.getMessage());
            throw new DomainException(
                    ErrorCodes.INTERNAL_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialise analysis payload");
        }
    }

    private ReportPayload deserialise(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, ReportPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialise cached ReportPayload: {}", e.getMessage());
            throw new DomainException(
                    ErrorCodes.INTERNAL_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cached analysis payload is unreadable");
        }
    }

    /**
     * Builds a new PairAnalysis instance with the five Claude text fields
     * populated. All other fields are copied verbatim.
     */
    private PairAnalysis withClaudeFields(PairAnalysis a, PairNarrativeFields f) {
        return new PairAnalysis(
                a.pair(),
                a.structureByTimeframe(),
                a.activeZone(),
                a.confidence(),
                a.m15Confirmation(),
                a.signalState(),
                a.tradePlan(),
                a.fundamental(),
                a.htfConflict(),
                a.fundamentalConflict(),
                f.setupStatus(),
                f.shortReasoning(),
                f.executiveReasoning(),
                f.invalidationNote(),
                f.fundamentalSummary(),
                a.layer());
    }

    private List<PairAnalysis> orderBestFirst(List<PairAnalysis> analyses, String bestPair) {
        if (bestPair == null) {
            return analyses;
        }
        List<PairAnalysis> sorted = new ArrayList<>(analyses);
        sorted.sort(Comparator.comparingInt(a -> bestPair.equals(a.pair()) ? 0 : 1));
        return sorted;
    }

    @PreDestroy
    void shutdown() {
        narrativePool.shutdown();
        try {
            if (!narrativePool.awaitTermination(5, TimeUnit.SECONDS)) {
                narrativePool.shutdownNow();
            }
        } catch (InterruptedException e) {
            narrativePool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record NarrativeOutcome(
            List<PairAnalysis> analyses,
            String mode,
            short invalidCount,
            String megaSummary
    ) {}

    public record AnalysisResult(MarketAnalysis row, ReportPayload payload) {}
}
