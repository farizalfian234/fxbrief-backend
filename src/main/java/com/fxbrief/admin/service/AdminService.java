package com.fxbrief.admin.service;

import com.fxbrief.admin.dto.AdminAccountStatusView;
import com.fxbrief.admin.dto.AdminTopUpRequest;
import com.fxbrief.admin.dto.AdminTopUpView;
import com.fxbrief.auth.security.AuthenticatedUser;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.report.entity.UserReport;
import com.fxbrief.report.repository.UserReportRepository;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.entity.SubscriptionAuditLog;
import com.fxbrief.subscription.entity.SubscriptionPlan;
import com.fxbrief.subscription.repository.SubscriptionAuditLogRepository;
import com.fxbrief.subscription.repository.SubscriptionPlanRepository;
import com.fxbrief.subscription.repository.SubscriptionRepository;
import com.fxbrief.subscription.service.ForexMarketClock;
import com.fxbrief.user.entity.SystemRole;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Admin mutating operations: manual top-up and account activation/deactivation.
 * Every successful state-changing operation writes a row to
 * {@code subscription_audit_logs} with the acting admin recorded as
 * {@code performed_by}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    static final String ACTION_ADMIN_TOP_UP = "ADMIN_TOP_UP";
    static final String ACTION_ADMIN_PLAN_CHANGE = "ADMIN_PLAN_CHANGE";
    static final String ACTION_ADMIN_ACTIVATION = "ADMIN_ACTIVATION";
    static final String ACTION_ADMIN_DEACTIVATION = "ADMIN_DEACTIVATION";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionAuditLogRepository auditLogRepository;
    private final UserReportRepository userReportRepository;
    private final ForexMarketClock forexMarketClock;

    /**
     * Performs a manual top-up: assigns the target plan, adds 20 reports with
     * carry-over (remaining + 20 = new total), flips {@code has_ever_paid} on
     * if not already set, and propagates the new plan to today's unarchived
     * report row when one exists (so a subsequent {@code GET /reports/today}
     * call returns the upgraded plan response without regenerating).
     *
     * The audit action depends on whether the plan changed:
     * {@code ADMIN_PLAN_CHANGE} when old plan and new plan differ;
     * {@code ADMIN_TOP_UP} when the plan is unchanged but reports were added.
     */
    @Transactional
    public AdminTopUpView manualTopUp(Long targetUserId, AdminTopUpRequest request,
                                     AuthenticatedUser actingAdmin) {
        PlanCode targetCode = parseTopUpPlan(request.plan());

        User targetUser = requireNonAdminUser(targetUserId);
        Subscription subscription = subscriptionRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Subscription not found"));

        SubscriptionPlan oldPlan = subscription.getPlan();
        int oldRemaining = subscription.getRemainingReports();

        SubscriptionPlan newPlan = planRepository.getReferenceById(targetCode.getId());
        int additional = newPlan.getReportCount();
        int newRemaining = oldRemaining + additional;
        boolean planChanged = !oldPlan.getId().equals(newPlan.getId());

        subscription.setPlan(newPlan);
        subscription.setRemainingReports(newRemaining);

        if (!targetUser.isHasEverPaid()) {
            targetUser.setHasEverPaid(true);
        }

        if (planChanged) {
            propagateNewPlanToTodayUnarchivedReport(targetUserId, targetCode.getId());
        }

        writeAuditLog(
                planChanged ? ACTION_ADMIN_PLAN_CHANGE : ACTION_ADMIN_TOP_UP,
                targetUser,
                oldPlan,
                newPlan,
                oldRemaining,
                newRemaining,
                actingAdmin);

        log.info("Admin top-up: adminId={} targetUserId={} oldPlan={} newPlan={} oldRemaining={} newRemaining={}",
                actingAdmin.id(), targetUserId, oldPlan.getName(), newPlan.getName(),
                oldRemaining, newRemaining);

        return new AdminTopUpView(
                targetUserId,
                newPlan.getName(),
                newRemaining,
                targetUser.isHasEverPaid(),
                planChanged);
    }

    @Transactional
    public AdminAccountStatusView activate(Long targetUserId, AuthenticatedUser actingAdmin) {
        User targetUser = requireNonAdminUser(targetUserId);
        if (targetUser.isActive()) {
            return new AdminAccountStatusView(targetUserId, true);
        }
        targetUser.setActive(true);
        writeStatusAuditLog(ACTION_ADMIN_ACTIVATION, targetUser, actingAdmin);
        log.info("Admin activation: adminId={} targetUserId={}", actingAdmin.id(), targetUserId);
        return new AdminAccountStatusView(targetUserId, true);
    }

    @Transactional
    public AdminAccountStatusView deactivate(Long targetUserId, AuthenticatedUser actingAdmin) {
        User targetUser = requireNonAdminUser(targetUserId);
        if (!targetUser.isActive()) {
            return new AdminAccountStatusView(targetUserId, false);
        }
        targetUser.setActive(false);
        writeStatusAuditLog(ACTION_ADMIN_DEACTIVATION, targetUser, actingAdmin);
        log.info("Admin deactivation: adminId={} targetUserId={}", actingAdmin.id(), targetUserId);
        return new AdminAccountStatusView(targetUserId, false);
    }

    private User requireNonAdminUser(Long targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.USER_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "User not found"));
        if (user.getRole() != null && user.getRole().getId() != null
                && user.getRole().getId() == SystemRole.ADMIN.getId()) {
            throw new DomainException(
                    ErrorCodes.CANNOT_MODIFY_ADMIN,
                    HttpStatus.FORBIDDEN,
                    "Admin users cannot be modified through the admin panel");
        }
        return user;
    }

    private void propagateNewPlanToTodayUnarchivedReport(Long userId, short newPlanId) {
        LocalDate today = forexMarketClock.currentForexMarketDate();
        Optional<UserReport> todayReport = userReportRepository
                .findByUserIdAndForexMarketDateAndArchivedFalse(userId, today);
        todayReport.ifPresent(report -> report.setPlanAtGeneration(newPlanId));
    }

    private void writeAuditLog(
            String action,
            User targetUser,
            SubscriptionPlan oldPlan,
            SubscriptionPlan newPlan,
            int oldRemaining,
            int newRemaining,
            AuthenticatedUser actingAdmin) {
        SubscriptionAuditLog entry = new SubscriptionAuditLog();
        entry.setUser(targetUser);
        entry.setAction(action);
        entry.setOldPlan(oldPlan);
        entry.setNewPlan(newPlan);
        entry.setOldRemaining(oldRemaining);
        entry.setNewRemaining(newRemaining);
        entry.setPerformedBy(userRepository.getReferenceById(actingAdmin.id()));
        auditLogRepository.save(entry);
    }

    private void writeStatusAuditLog(String action, User targetUser, AuthenticatedUser actingAdmin) {
        SubscriptionAuditLog entry = new SubscriptionAuditLog();
        entry.setUser(targetUser);
        entry.setAction(action);
        Subscription subscription = subscriptionRepository.findByUserId(targetUser.getId())
                .orElse(null);
        if (subscription != null) {
            entry.setOldPlan(subscription.getPlan());
            entry.setNewPlan(subscription.getPlan());
            entry.setOldRemaining(subscription.getRemainingReports());
            entry.setNewRemaining(subscription.getRemainingReports());
        }
        entry.setPerformedBy(userRepository.getReferenceById(actingAdmin.id()));
        auditLogRepository.save(entry);
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
}
