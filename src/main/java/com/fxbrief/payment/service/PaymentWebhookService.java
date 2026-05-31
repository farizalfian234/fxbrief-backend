package com.fxbrief.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.payment.entity.PaymentCallback;
import com.fxbrief.payment.entity.PaymentStatus;
import com.fxbrief.payment.entity.PaymentTransaction;
import com.fxbrief.payment.repository.PaymentCallbackRepository;
import com.fxbrief.payment.repository.PaymentTransactionRepository;
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
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Processes Midtrans payment notifications. The handler is idempotent: the signature is
 * verified, the verified callback is recorded, and the report credit is applied at most
 * once per order regardless of how many times Midtrans re-delivers the notification.
 *
 * <p>On a verified successful payment the subscription mutation mirrors the admin manual
 * top-up (AdminService): assign the paid plan, add 20 reports with carry-over, flip
 * {@code has_ever_paid}, propagate the new plan to today's unarchived report row when one
 * exists, and write a {@code TOP_UP} audit row with {@code performed_by} equal to the
 * paying user (D-034).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    static final String ACTION_TOP_UP = "TOP_UP";

    /** Midtrans transaction_status values that represent a completed payment. */
    private static final Set<String> SUCCESS_STATUSES = Set.of("capture", "settlement");
    /** Terminal unsuccessful states. */
    private static final Set<String> FAILURE_STATUSES = Set.of("deny", "cancel", "expire", "failure");

    private final ObjectMapper objectMapper;
    private final MidtransSignatureVerifier signatureVerifier;
    private final PaymentCallbackRepository callbackRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final UserReportRepository userReportRepository;
    private final ForexMarketClock forexMarketClock;

    @Transactional
    public void handleNotification(String rawPayload) {
        JsonNode payload = parsePayload(rawPayload);

        String orderId = payload.path("order_id").asText(null);
        String statusCode = payload.path("status_code").asText(null);
        String grossAmount = payload.path("gross_amount").asText(null);
        String signature = payload.path("signature_key").asText(null);
        String transactionStatus = payload.path("transaction_status").asText(null);
        String fraudStatus = payload.path("fraud_status").asText(null);

        if (!signatureVerifier.isValid(orderId, statusCode, grossAmount, signature)) {
            log.warn("Rejected Midtrans webhook with invalid signature: orderId={}", orderId);
            throw new DomainException(
                    ErrorCodes.INVALID_PAYMENT_SIGNATURE,
                    HttpStatus.UNAUTHORIZED,
                    "Invalid Midtrans signature");
        }

        recordCallback(orderId, rawPayload);

        Optional<PaymentTransaction> maybeTransaction =
                transactionRepository.findByMidtransOrderIdForUpdate(orderId);
        if (maybeTransaction.isEmpty()) {
            log.warn("Midtrans webhook for unknown order id: orderId={}", orderId);
            return;
        }
        PaymentTransaction transaction = maybeTransaction.get();

        if (transaction.getStatus() == PaymentStatus.PAID) {
            log.info("Duplicate Midtrans webhook for already-paid order: orderId={}", orderId);
            return;
        }

        if (isSuccessful(transactionStatus, fraudStatus)) {
            applySuccessfulPayment(transaction);
            transaction.setStatus(PaymentStatus.PAID);
            transaction.setPaidAt(Instant.now());
            log.info("Payment confirmed and credited: orderId={} userId={} plan={}",
                    orderId, transaction.getUserId(), transaction.getPlan());
        } else if (isFailure(transactionStatus)) {
            transaction.setStatus(PaymentStatus.FAILED);
            log.info("Payment failed: orderId={} status={}", orderId, transactionStatus);
        } else {
            log.info("Payment pending, no state change: orderId={} status={}", orderId, transactionStatus);
        }
    }

    private void applySuccessfulPayment(PaymentTransaction transaction) {
        Long userId = transaction.getUserId();
        PlanCode targetCode = resolvePlanCode(transaction.getPlan());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.USER_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "User not found"));

        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Subscription not found"));

        SubscriptionPlan oldPlan = subscription.getPlan();
        int oldRemaining = subscription.getRemainingReports();

        SubscriptionPlan newPlan = planRepository.getReferenceById(targetCode.getId());
        int newRemaining = oldRemaining + newPlan.getReportCount();

        subscription.setPlan(newPlan);
        subscription.setRemainingReports(newRemaining);

        if (!user.isHasEverPaid()) {
            user.setHasEverPaid(true);
        }

        propagateNewPlanToTodayUnarchivedReport(userId, targetCode.getId());

        writeTopUpAuditLog(user, oldPlan, newPlan, oldRemaining, newRemaining);
    }

    /**
     * Updates plan_at_generation on today's unarchived report row if one exists, so the
     * next GET /reports/today returns the upgraded plan response without regeneration.
     * Applied on every confirmed top-up, not only when the plan tier changes.
     */
    private void propagateNewPlanToTodayUnarchivedReport(Long userId, short newPlanId) {
        LocalDate today = forexMarketClock.currentForexMarketDate();
        Optional<UserReport> todayReport = userReportRepository
                .findByUserIdAndForexMarketDateAndArchivedFalse(userId, today);
        todayReport.ifPresent(report -> report.setPlanAtGeneration(newPlanId));
    }

    private void writeTopUpAuditLog(User user, SubscriptionPlan oldPlan, SubscriptionPlan newPlan,
                                    int oldRemaining, int newRemaining) {
        SubscriptionAuditLog entry = new SubscriptionAuditLog();
        entry.setUser(user);
        entry.setAction(ACTION_TOP_UP);
        entry.setOldPlan(oldPlan);
        entry.setNewPlan(newPlan);
        entry.setOldRemaining(oldRemaining);
        entry.setNewRemaining(newRemaining);
        entry.setPerformedBy(user);
        auditLogRepository.save(entry);
    }

    private void recordCallback(String orderId, String rawPayload) {
        PaymentCallback callback = new PaymentCallback();
        callback.setMidtransOrderId(orderId);
        callback.setRawPayload(rawPayload);
        callback.setProcessedAt(Instant.now());
        callbackRepository.save(callback);
    }

    private boolean isSuccessful(String transactionStatus, String fraudStatus) {
        if (transactionStatus == null || !SUCCESS_STATUSES.contains(transactionStatus)) {
            return false;
        }
        // For card transactions Midtrans reports "capture" with a fraud_status that must
        // be "accept" before the funds are settled; non-card methods omit fraud_status.
        return fraudStatus == null || fraudStatus.isBlank() || "accept".equals(fraudStatus);
    }

    private boolean isFailure(String transactionStatus) {
        return transactionStatus != null && FAILURE_STATUSES.contains(transactionStatus);
    }

    private PlanCode resolvePlanCode(String code) {
        if (PlanCode.BASIC.getCode().equals(code)) {
            return PlanCode.BASIC;
        }
        if (PlanCode.PREMIUM.getCode().equals(code)) {
            return PlanCode.PREMIUM;
        }
        throw new DomainException(
                ErrorCodes.INVALID_PLAN_FOR_TOP_UP,
                HttpStatus.BAD_REQUEST,
                "Stored transaction plan is not a valid top-up target: " + code);
    }

    private JsonNode parsePayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new DomainException(
                    ErrorCodes.MALFORMED_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "Empty webhook payload");
        }
        try {
            return objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            throw new DomainException(
                    ErrorCodes.MALFORMED_REQUEST,
                    HttpStatus.BAD_REQUEST,
                    "Malformed webhook payload");
        }
    }
}
