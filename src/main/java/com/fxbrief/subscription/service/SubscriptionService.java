package com.fxbrief.subscription.service;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.subscription.dto.CarryOverView;
import com.fxbrief.subscription.dto.PlanView;
import com.fxbrief.subscription.dto.RemainingReportsView;
import com.fxbrief.subscription.dto.SubscriptionView;
import com.fxbrief.subscription.dto.TopUpInitiationView;
import com.fxbrief.subscription.dto.TopUpRequest;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.entity.SubscriptionPlan;
import com.fxbrief.subscription.repository.SubscriptionPlanRepository;
import com.fxbrief.subscription.repository.SubscriptionRepository;
import com.fxbrief.subscription.repository.SubscriptionUsageRepository;
import com.fxbrief.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionUsageRepository usageRepository;
    private final ForexMarketClock forexMarketClock;

    /**
     * Provisions the initial Free subscription for a newly registered user.
     *
     * Granted report count follows the has_ever_paid rule (PRD §5.2): a fresh user
     * receives the Free plan's full quota; a user flagged has_ever_paid receives 0.
     * At registration time the flag is always false; the conditional preserves the
     * invariant for any future caller that provisions Free post-registration.
     *
     * Idempotent: if a subscription already exists for the user, no-op.
     */
    @Transactional
    public void provisionFreePlan(User user) {
        if (subscriptionRepository.findByUserId(user.getId()).isPresent()) {
            return;
        }

        SubscriptionPlan freePlan = planRepository.getReferenceById(PlanCode.FREE.getId());
        int initialReports = user.isHasEverPaid() ? 0 : freePlan.getReportCount();

        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setPlan(freePlan);
        subscription.setRemainingReports(initialReports);
        subscriptionRepository.save(subscription);

        log.info("Provisioned Free subscription for user id={} remainingReports={}",
                user.getId(), initialReports);
    }

    @Transactional(readOnly = true)
    public SubscriptionView getSubscriptionFor(Long userId) {
        Subscription subscription = loadSubscription(userId);
        SubscriptionPlan basePlan = subscription.getPlan();
        SubscriptionPlan effectivePlan = resolveEffectivePlan(subscription);

        return new SubscriptionView(
                toPlanView(basePlan),
                toPlanView(effectivePlan),
                subscription.getRemainingReports(),
                subscription.getUser().isHasEverPaid(),
                forexMarketClock.isMarketOpen());
    }

    @Transactional(readOnly = true)
    public RemainingReportsView getRemainingReportsFor(Long userId) {
        return new RemainingReportsView(loadSubscription(userId).getRemainingReports());
    }

    /**
     * Initiates a top-up and returns the carry-over preview for the frontend warning
     * modal. No persistent state is changed: the actual plan change and report-count
     * mutation are performed by Phase 5B on Midtrans payment confirmation.
     *
     * paymentUrl is null in Phase 2B — the field shape is fixed so the frontend
     * contract is stable, and Phase 5B will populate it from the Midtrans charge
     * response.
     */
    @Transactional(readOnly = true)
    public TopUpInitiationView initiateTopUp(Long userId, TopUpRequest request) {
        PlanCode targetCode = parseTopUpPlan(request.plan());
        Subscription subscription = loadSubscription(userId);

        SubscriptionPlan targetPlan = planRepository.getReferenceById(targetCode.getId());
        int remaining = subscription.getRemainingReports();
        int additional = targetPlan.getReportCount();
        int newTotal = remaining + additional;

        boolean warningFlag = remaining > 0;
        CarryOverView carryOver = new CarryOverView(remaining, additional, newTotal);

        return new TopUpInitiationView(
                toPlanView(targetPlan),
                null,
                warningFlag,
                carryOver);
    }

    private Subscription loadSubscription(Long userId) {
        return subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Subscription not found"));
    }

    /**
     * Resolves the plan label the frontend should render and use to gate history and
     * generation. Per PRD §5.4, a Basic/Premium subscription whose remaining reports
     * reached zero on a previous forex market day lapses to Free behaviour for the
     * label, history lock, and generation gate. The base plan stored on the row is
     * unchanged; only the read-time projection downgrades.
     *
     * The "previous forex market day" check is materialised by the absence of a
     * subscription_usage row for the current forex market date: while a user is in
     * the same forex day they consumed their last report, they are not lapsed; once
     * the 22:00 UTC boundary crosses, no usage row exists for the new day and the
     * lapse takes effect. A top-up restores remaining > 0 and the condition can no
     * longer fire.
     */
    private SubscriptionPlan resolveEffectivePlan(Subscription subscription) {
        SubscriptionPlan basePlan = subscription.getPlan();
        if (basePlan.getId().equals(PlanCode.FREE.getId())) {
            return basePlan;
        }
        if (subscription.getRemainingReports() > 0) {
            return basePlan;
        }
        boolean usedToday = usageRepository.existsByUserIdAndForexMarketDate(
                subscription.getUser().getId(),
                forexMarketClock.currentForexMarketDate());
        if (usedToday) {
            return basePlan;
        }
        return planRepository.getReferenceById(PlanCode.FREE.getId());
    }

    private PlanCode parseTopUpPlan(String plan) {
        if (plan == null) {
            throw invalidTopUpPlan();
        }
        String normalised = plan.trim().toUpperCase();
        if (normalised.equals(PlanCode.BASIC.getCode())) {
            return PlanCode.BASIC;
        }
        if (normalised.equals(PlanCode.PREMIUM.getCode())) {
            return PlanCode.PREMIUM;
        }
        throw invalidTopUpPlan();
    }

    private DomainException invalidTopUpPlan() {
        return new DomainException(
                ErrorCodes.INVALID_PLAN_FOR_TOP_UP,
                HttpStatus.BAD_REQUEST,
                "Top-up target must be BASIC or PREMIUM");
    }

    private PlanView toPlanView(SubscriptionPlan plan) {
        return new PlanView(
                plan.getId(),
                plan.getName(),
                plan.getPrice(),
                plan.getReportCount());
    }
}
