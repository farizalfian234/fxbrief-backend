package com.fxbrief.report.service;

import com.fxbrief.analysis.dto.ReportPayload;
import com.fxbrief.analysis.entity.MarketAnalysis;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.entity.UserReport;
import com.fxbrief.report.repository.UserReportRepository;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.entity.SubscriptionPlan;
import com.fxbrief.subscription.entity.SubscriptionUsage;
import com.fxbrief.subscription.repository.SubscriptionRepository;
import com.fxbrief.subscription.repository.SubscriptionUsageRepository;
import com.fxbrief.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Persistence boundary for {@code user_reports} plus the linked
 * {@code subscriptions} decrement and {@code subscription_usage} insert.
 *
 * Lives in its own bean so its {@code @Transactional} method is invoked
 * through Spring's AOP proxy from {@link ReportGenerationService} —
 * self-invocation would bypass the annotation (DECISIONS D-039).
 *
 * The advisory lock taken at the top of {@link #commit} ties the
 * decrement, the usage write, and the report write to the same
 * transaction-scoped lock so a concurrent double-tap from the same user
 * serialises here even after the analysis engine has produced output.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserReportWriter {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionUsageRepository usageRepository;
    private final UserReportRepository userReportRepository;
    private final AdvisoryLockService advisoryLockService;
    private final EntityManager entityManager;

    public record CommitResult(UserReport report, int remainingReports, boolean reportsExhausted) {}

    @Transactional
    public CommitResult commit(Long userId, LocalDate forexDate, MarketAnalysis marketAnalysisRef,
                               ReportPayload payload) {
        advisoryLockService.lockUser(userId);

        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Subscription not found"));

        User user = subscription.getUser();
        if (!user.isActive()) {
            throw new DomainException(
                    ErrorCodes.ACCOUNT_INACTIVE,
                    HttpStatus.FORBIDDEN,
                    "Account is inactive");
        }

        if (userReportRepository.findByUserIdAndForexMarketDate(userId, forexDate).isPresent()) {
            throw new DomainException(
                    ErrorCodes.DAILY_LIMIT_REACHED,
                    HttpStatus.CONFLICT,
                    "Report already generated for today");
        }

        if (subscription.getRemainingReports() <= 0) {
            throw new DomainException(
                    ErrorCodes.NO_REMAINING_REPORTS,
                    HttpStatus.CONFLICT,
                    "No remaining reports");
        }

        // PRD §5.5: report count subtracted only when the report has content.
        // "Markets consolidating" is the zero-content case.
        boolean hasContent = !payload.marketsConsolidating();
        SubscriptionPlan basePlan = subscription.getPlan();
        // The MarketAnalysis reference may be detached (the engine ran outside
        // this transaction). Resolve a managed reference by id for the FK without
        // forcing a SELECT — getReference is lazy.
        MarketAnalysis marketAnalysis = entityManager.getReference(
                MarketAnalysis.class, marketAnalysisRef.getId());
        String summary = marketAnalysisRef.getSummary();

        if (hasContent) {
            subscription.setRemainingReports(subscription.getRemainingReports() - 1);
            subscriptionRepository.save(subscription);

            SubscriptionUsage usage = new SubscriptionUsage();
            usage.setUser(user);
            usage.setForexMarketDate(forexDate);
            try {
                usageRepository.saveAndFlush(usage);
            } catch (DataIntegrityViolationException e) {
                throw new DomainException(
                        ErrorCodes.DAILY_LIMIT_REACHED,
                        HttpStatus.CONFLICT,
                        "Report already generated for today");
            }
        }

        UserReport report = new UserReport();
        report.setUser(user);
        report.setMarketAnalysis(marketAnalysis);
        report.setSummary(summary);
        report.setForexMarketDate(forexDate);
        report.setCountedAgainstLimit(hasContent);
        report.setPlanAtGeneration(basePlan.getId());
        report.setGeneratedAt(Instant.now());
        report.setArchived(false);
        try {
            userReportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(
                    ErrorCodes.DAILY_LIMIT_REACHED,
                    HttpStatus.CONFLICT,
                    "Report already generated for today");
        }

        int remaining = subscription.getRemainingReports();
        boolean exhausted = hasContent && remaining == 0;
        log.info("Generated report id={} user={} counted={} remaining={} exhausted={}",
                report.getId(), userId, hasContent, remaining, exhausted);

        return new CommitResult(report, remaining, exhausted);
    }
}
