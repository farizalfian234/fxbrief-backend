package com.fxbrief.analysis.service;

import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.analysis.entity.NarrativeQaLog;
import com.fxbrief.analysis.repository.MarketAnalysisRepository;
import com.fxbrief.analysis.repository.NarrativeQaLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for {@code market_analysis} and {@code narrative_qa_log}.
 * Lives in its own bean so each call goes through Spring's @Transactional
 * proxy, never holding a connection across the Claude HTTP fetch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketAnalysisWriter {

    private final MarketAnalysisRepository marketAnalysisRepository;
    private final NarrativeQaLogRepository narrativeQaLogRepository;

    /**
     * Atomic save for the shared analysis row. The unique constraint on
     * {@code fetch_id} guards against the rare race where two concurrent
     * user requests both miss the cache and try to insert: the second
     * caller catches {@link DataIntegrityViolationException} and re-reads
     * the row written by the first.
     */
    @Transactional
    public MarketAnalysis saveOrReturnExisting(UUID fetchId, String payloadJson,
                                               String summary, String narrativeMode,
                                               short invalidPairCount,
                                               Instant marketDataFetchedAt,
                                               Instant calendarFetchedAt) {
        MarketAnalysis row = new MarketAnalysis();
        row.setFetchId(fetchId);
        row.setPayload(payloadJson);
        row.setSummary(summary);
        row.setNarrativeMode(narrativeMode);
        row.setInvalidPairCount(invalidPairCount);
        row.setMarketDataFetchedAt(marketDataFetchedAt);
        row.setCalendarFetchedAt(calendarFetchedAt);
        try {
            return marketAnalysisRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent insert lost the race for fetch_id {}; reading existing row", fetchId);
            Optional<MarketAnalysis> existing = marketAnalysisRepository.findByFetchId(fetchId);
            return existing.orElseThrow(() -> e);
        }
    }

    @Transactional
    public void logShadow(UUID fetchId, String pair, String megaNarrative,
                          String perPairNarrative, boolean diverged, String divergenceReason) {
        NarrativeQaLog log = new NarrativeQaLog();
        log.setFetchId(fetchId);
        log.setPair(pair);
        log.setMegaNarrative(megaNarrative);
        log.setPerPairNarrative(perPairNarrative);
        log.setDiverged(diverged);
        log.setDivergenceReason(divergenceReason);
        narrativeQaLogRepository.save(log);
    }
}
