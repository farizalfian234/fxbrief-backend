package com.fxbrief.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.dto.ReportPayload;
import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.analysis.service.AnalysisEngine;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.dto.ReportView;
import com.fxbrief.report.entity.UserReport;
import com.fxbrief.report.repository.UserReportRepository;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.repository.SubscriptionRepository;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.service.ForexMarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
 * Both the generate response and the today-read pass through
 * {@link ReportPayloadNarrower#narrowForLive} so Free and Basic users
 * receive only the data their UI renders (D-055). The stored
 * {@code market_analysis.payload} is never mutated — narrowing is purely
 * a read-time projection over the deserialised structure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGenerationService {

    private final ReportGenerationPreCheck preCheck;
    private final UserReportWriter userReportWriter;
    private final AnalysisEngine analysisEngine;
    private final SubscriptionRepository subscriptionRepository;
    private final UserReportRepository userReportRepository;
    private final ForexMarketClock forexMarketClock;
    private final ObjectMapper objectMapper;
    private final ReportPayloadNarrower payloadNarrower;

    public ReportView generate(Long userId) {
        LocalDate forexDate = forexMarketClock.currentForexMarketDate();
        preCheck.verify(userId, forexDate);

        AnalysisEngine.AnalysisResult analysis = analysisEngine.runAnalysis();

        UserReportWriter.CommitResult result = userReportWriter.commit(
                userId, forexDate, analysis.row(), analysis.payload());

        return toView(result.report(), analysis.payload(),
                result.remainingReports(), result.reportsExhausted());
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
        ReportPayload payload = deserialise(row.getPayload());

        return Optional.of(toView(report, payload,
                subscription.getRemainingReports(), false));
    }

    private ReportView toView(UserReport report, ReportPayload payload,
                              int remainingReports, boolean reportsExhausted) {
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
                reportsExhausted);
    }

    private ReportPayload deserialise(String payloadJson) {
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

    private String planCodeFor(short planId) {
        for (PlanCode code : PlanCode.values()) {
            if (code.getId() == planId) {
                return code.getCode();
            }
        }
        return String.valueOf(planId);
    }
}
