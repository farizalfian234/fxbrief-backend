package com.fxbrief.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.dto.ReportPayload;
import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.analysis.service.AnalysisEngine;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.dto.GenerateReportRequest;
import com.fxbrief.report.dto.PreferenceSnapshot;
import com.fxbrief.report.dto.ReportView;
import com.fxbrief.report.entity.UserReport;
import com.fxbrief.report.repository.UserReportRepository;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.repository.SubscriptionRepository;
import com.fxbrief.subscription.service.ForexMarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Public entry point for report generation and the dashboard's "today's
 * report" read.
 *
 * Generation is split across three beans so each {@code @Transactional}
 * method runs through Spring's AOP proxy:
 * <ol>
 *   <li>{@link ReportGenerationPreCheck#verify} — read-only pre-check.</li>
 *   <li>{@link AnalysisEngine#runAnalysis} — no outer transaction; the
 *       engine manages its own short writes per DECISIONS D-039.</li>
 *   <li>{@link UserReportWriter#commit} — advisory lock, decrement, write.</li>
 * </ol>
 *
 * <h2>Preference scoring (Addition 3, D-056)</h2>
 * After the engine returns and before the writer commits, this service
 * resolves the active preference (override or persisted), invokes
 * {@link PreferenceScorer} to produce per-pair {@code finalDisplayScores},
 * and uses {@link PayloadReorderer} to reorder the {@link ReportPayload}
 * so the highest-scored pair is the new {@code bestPair} and the pairs
 * list runs best-first by final score.
 *
 * The shared {@code market_analysis.payload} JSONB is never modified —
 * reordering produces a new {@code ReportPayload} record that lives only
 * in this request. The snapshot + score map are persisted on the
 * user-specific {@code user_reports} row so subsequent reads of this
 * report (today / archived) reproduce the same view deterministically,
 * even if the user's persisted preference changes later.
 *
 * <p>Both the generate response and the today-read pass through
 * {@link ReportPayloadNarrower#narrowForLive} so Free and Basic users
 * receive only the data their UI renders (D-055).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGenerationService {

    private static final TypeReference<Map<String, Double>> SCORE_MAP_TYPE = new TypeReference<>() {};

    private final ReportGenerationPreCheck preCheck;
    private final UserReportWriter userReportWriter;
    private final AnalysisEngine analysisEngine;
    private final SubscriptionRepository subscriptionRepository;
    private final UserReportRepository userReportRepository;
    private final ForexMarketClock forexMarketClock;
    private final ObjectMapper objectMapper;
    private final ReportPayloadNarrower payloadNarrower;
    private final PreferenceResolver preferenceResolver;
    private final PreferenceScorer preferenceScorer;
    private final PayloadReorderer payloadReorderer;

    public ReportView generate(Long userId, GenerateReportRequest request) {
        LocalDate forexDate = forexMarketClock.currentForexMarketDate();
        preCheck.verify(userId, forexDate);

        // Resolve preference before running the engine so a partial-override
        // 400 is returned without paying the Claude cost.
        Optional<PreferenceSnapshot> snapshotOpt = preferenceResolver.resolve(userId, request);

        AnalysisEngine.AnalysisResult analysis = analysisEngine.runAnalysis();
        ReportPayload payload = analysis.payload();

        Map<String, Double> scores = null;
        Map<String, Boolean> matches = null;
        if (snapshotOpt.isPresent()) {
            scores = preferenceScorer.score(payload, snapshotOpt.get());
            // Matches must be derived from the SAME payload as scores —
            // before reorder so each pair's snapshot-based compatibility
            // is correctly evaluated. The reorder itself doesn't change
            // per-pair properties; either order is fine, but doing it
            // before keeps the read-path and write-path code symmetric.
            matches = preferenceScorer.matches(payload, snapshotOpt.get());
            payload = payloadReorderer.reorder(payload, scores);
        }

        UserReportWriter.CommitResult result = userReportWriter.commit(
                userId, forexDate, analysis.row(), payload,
                snapshotOpt.orElse(null), scores);

        return toView(result.report(), payload,
                result.remainingReports(), result.reportsExhausted(),
                snapshotOpt.orElse(null), scores, matches);
    }

    @Transactional(readOnly = true)
    public Optional<ReportView> getTodayReport(Long userId) {
        LocalDate forexDate = forexMarketClock.currentForexMarketDate();
        Optional<UserReport> existing = userReportRepository
                .findByUserIdAndForexMarketDate(userId, forexDate);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Subscription not found"));

        UserReport report = existing.get();
        MarketAnalysis row = report.getMarketAnalysis();
        ReportPayload payload = deserialisePayload(row.getPayload());

        // Apply the report's stored snapshot — never the user's current
        // preference. Once a report is generated, its view is frozen.
        PreferenceSnapshot snapshot = deserialiseSnapshot(report.getPreferenceSnapshot());
        Map<String, Double> scores = deserialiseScores(report.getFinalDisplayScores());
        Map<String, Boolean> matches = null;
        if (snapshot != null) {
            // Derive matches from the stored snapshot against the freshly-
            // deserialised payload. Not persisted — recomputed on every read
            // so a future threshold tweak applies uniformly to all rows
            // (D-062). The snapshot itself is frozen, so the per-pair user
            // score is deterministic.
            matches = preferenceScorer.matches(payload, snapshot);
        }
        if (scores != null) {
            payload = payloadReorderer.reorder(payload, scores);
        }

        return Optional.of(toView(report, payload,
                subscription.getRemainingReports(), false,
                snapshot, scores, matches));
    }

    private ReportView toView(UserReport report, ReportPayload payload,
                              int remainingReports, boolean reportsExhausted,
                              PreferenceSnapshot snapshot, Map<String, Double> scores,
                              Map<String, Boolean> matches) {
        Object narrowedPayload = payloadNarrower.narrowForLive(
                payload, report.getPlanAtGeneration());

        return new ReportView(
                report.getId(),
                report.getSummary(),
                narrowedPayload,
                report.getForexMarketDate(),
                report.getGeneratedAt(),
                planCodeFor(report.getPlanAtGeneration()),
                report.isCountedAgainstLimit(),
                remainingReports,
                reportsExhausted,
                snapshot,
                scores,
                matches);
    }

    private ReportPayload deserialisePayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, ReportPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialise stored ReportPayload: {}", e.getMessage());
            throw new DomainException(
                    ErrorCodes.INTERNAL_ERROR,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored report payload is unreadable");
        }
    }

    private PreferenceSnapshot deserialiseSnapshot(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PreferenceSnapshot.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialise stored preferenceSnapshot: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Double> deserialiseScores(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SCORE_MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialise stored finalDisplayScores: {}", e.getMessage());
            return null;
        }
    }

    private String planCodeFor(short planId) {
        for (PlanCode code : PlanCode.values()) {
            if (code.getId() == planId) {
                return code.getCode();
            }
        }
        return String.valueOf(planId);
    }
}
