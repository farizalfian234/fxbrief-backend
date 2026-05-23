package com.fxbrief.report.service;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.repository.SubscriptionRepository;
import com.fxbrief.subscription.repository.SubscriptionUsageRepository;
import com.fxbrief.subscription.service.ForexMarketClock;
import com.fxbrief.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Read-only pre-flight verification before the expensive engine call.
 *
 * Separate from {@link UserReportWriter} so a failure here produces the
 * canonical error code without waiting through Claude. The same checks are
 * repeated in the writer under the advisory lock — the pre-check is a
 * latency optimisation, not the security barrier.
 */
@Component
@RequiredArgsConstructor
public class ReportGenerationPreCheck {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionUsageRepository usageRepository;
    private final ForexMarketClock forexMarketClock;

    @Transactional(readOnly = true)
    public void verify(Long userId, LocalDate forexDate) {
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

        if (!forexMarketClock.isMarketOpen()) {
            throw new DomainException(
                    ErrorCodes.MARKET_CLOSED,
                    HttpStatus.CONFLICT,
                    "Forex market is closed");
        }

        if (usageRepository.existsByUserIdAndForexMarketDate(userId, forexDate)) {
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
    }
}
