package com.fxbrief.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.analysis.dto.ReportPayload;
import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.dto.HistoryItemView;
import com.fxbrief.report.dto.HistoryView;
import com.fxbrief.report.dto.PreferenceSnapshot;
import com.fxbrief.report.dto.ReportView;
import com.fxbrief.report.entity.UserReport;
import com.fxbrief.report.repository.UserReportRepository;
import com.fxbrief.subscription.dto.PlanView;
import com.fxbrief.subscription.dto.SubscriptionView;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read surface for archived reports.
 *
 * Access is gated by the caller's current effective plan: a Basic or Premium
 * user whose remaining reports lapsed across a forex day boundary sees the
 * same locked response a Free user does (PRD §5.4). The lapse computation
 * lives on {@link SubscriptionService} and is consumed here via
 * {@code getSubscriptionFor} / {@code getEffectivePlanFor}.
 *
 * Content visibility follows the row's {@code planAtGeneration}, never the
 * caller's current plan. A report rendered at Basic depth on the day it was
 * generated is rendered at Basic depth forever, even after the user upgrades.
 * This preserves the immutability rule (PRD §8.1, D-054).
 *
 * <h2>Preference snapshot (Addition 3, D-056)</h2>
 * Every read of an archived row applies its stored
 * {@code final_display_scores} via {@link PayloadReorderer} before
 * narrowing. The order and best-pair pointer reflect the preference active
 * when the report was generated, never the user's current preference. A
 * user who changes their persisted preference later sees no change in
 * historical reports.
 *
 * <h2>Narrowing</h2>
 * The narrowing call uses {@code narrowForArchived} rather than
 * {@code narrowForLive} — archived responses carry the {@code bestPairView}
 * only and omit the {@code compactPreviews} field. Per the project owner's
 * "clean reading experience" rule, no upsell affordance is exposed inside
 * archived report content (D-055).
 *
 * The stored {@code market_analysis.payload} JSONB is never mutated; the
 * narrowing and reordering are pure read-time projections.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportHistoryService {

    static final int BASIC_VISIBLE_LIMIT = 10;
    static final int PREMIUM_PAGE_SIZE = 10;

    private static final TypeReference<Map<String, Double>> SCORE_MAP_TYPE = new TypeReference<>() {};

    private final UserReportRepository userReportRepository;
    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final ReportPayloadNarrower payloadNarrower;
    private final PayloadReorderer payloadReorderer;
    private final PreferenceScorer preferenceScorer;

    @Transactional(readOnly = true)
    public HistoryView getHistory(Long userId, int requestedPage) {
        PlanView effectivePlan = subscriptionService.getEffectivePlanFor(userId);

        if (isFreeEffective(effectivePlan)) {
            return lockedHistory();
        }

        if (isBasicEffective(effectivePlan)) {
            return basicHistory(userId);
        }

        return premiumHistory(userId, requestedPage);
    }

    @Transactional(readOnly = true)
    public ReportView getArchivedReport(Long userId, Long reportId) {
        SubscriptionView subscription = subscriptionService.getSubscriptionFor(userId);
        PlanView effectivePlan = subscription.effectivePlan();

        if (isFreeEffective(effectivePlan)) {
            throw notFound();
        }

        UserReport report = userReportRepository
                .findByIdAndUserIdAndArchivedTrue(reportId, userId)
                .orElseThrow(this::notFound);

        MarketAnalysis row = report.getMarketAnalysis();
        ReportPayload fullPayload = deserialisePayload(row.getPayload());

        PreferenceSnapshot snapshot = deserialiseSnapshot(report.getPreferenceSnapshot());
        Map<String, Double> scores = deserialiseScores(report.getFinalDisplayScores());
        Map<String, Boolean> matches = null;
        if (snapshot != null) {
            // Same recomputed-on-read pattern as getTodayReport: matches are
            // derived from the immutable snapshot against the freshly-
            // deserialised payload (D-062). Threshold changes apply
            // uniformly to live and archived reads.
            matches = preferenceScorer.matches(fullPayload, snapshot);
        }
        if (scores != null) {
            fullPayload = payloadReorderer.reorder(fullPayload, scores);
        }

        Object visiblePayload = payloadNarrower.narrowForArchived(
                fullPayload, report.getPlanAtGeneration());

        return new ReportView(
                report.getId(),
                report.getSummary(),
                visiblePayload,
                report.getForexMarketDate(),
                report.getGeneratedAt(),
                planCodeFor(report.getPlanAtGeneration()),
                report.isCountedAgainstLimit(),
                subscription.remainingReports(),
                false,
                snapshot,
                scores,
                matches);
    }

    private HistoryView basicHistory(Long userId) {
        long totalArchived = userReportRepository.countByUserIdAndArchivedTrue(userId);
        List<UserReport> rows = userReportRepository.findArchivedByUserId(
                userId, PageRequest.of(0, BASIC_VISIBLE_LIMIT));
        List<HistoryItemView> items = rows.stream().map(this::toItemView).toList();

        return new HistoryView(
                false,
                items,
                totalArchived,
                1,
                BASIC_VISIBLE_LIMIT,
                items.isEmpty() ? 0 : 1);
    }

    private HistoryView premiumHistory(Long userId, int requestedPage) {
        int zeroIndexedPage = Math.max(0, requestedPage - 1);
        Pageable pageable = PageRequest.of(zeroIndexedPage, PREMIUM_PAGE_SIZE);
        Page<UserReport> page = userReportRepository.findArchivedPageByUserId(userId, pageable);

        List<HistoryItemView> items = page.getContent().stream()
                .map(this::toItemView)
                .toList();

        return new HistoryView(
                false,
                items,
                page.getTotalElements(),
                zeroIndexedPage + 1,
                PREMIUM_PAGE_SIZE,
                page.getTotalPages());
    }

    private HistoryView lockedHistory() {
        return new HistoryView(true, Collections.emptyList(), 0L, 0, 0, 0);
    }

    private HistoryItemView toItemView(UserReport report) {
        return new HistoryItemView(
                report.getId(),
                report.getForexMarketDate(),
                planCodeFor(report.getPlanAtGeneration()),
                report.getSummary(),
                deserialiseSnapshot(report.getPreferenceSnapshot()));
    }

    private boolean isFreeEffective(PlanView plan) {
        return PlanCode.FREE.getId() == plan.id();
    }

    private boolean isBasicEffective(PlanView plan) {
        return PlanCode.BASIC.getId() == plan.id();
    }

    private String planCodeFor(short planId) {
        for (PlanCode code : PlanCode.values()) {
            if (code.getId() == planId) {
                return code.getCode();
            }
        }
        return String.valueOf(planId);
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

    private DomainException notFound() {
        return new DomainException(
                ErrorCodes.NOT_FOUND,
                HttpStatus.NOT_FOUND,
                "Report not found");
    }
}
