package com.fxbrief.payment.service;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.payment.client.MidtransClient;
import com.fxbrief.payment.config.MidtransProperties;
import com.fxbrief.payment.dto.SnapTransactionView;
import com.fxbrief.payment.entity.ExchangeRate;
import com.fxbrief.payment.entity.PaymentStatus;
import com.fxbrief.payment.entity.PaymentTransaction;
import com.fxbrief.payment.repository.PaymentTransactionRepository;
import com.fxbrief.subscription.dto.CarryOverView;
import com.fxbrief.subscription.dto.PlanView;
import com.fxbrief.subscription.dto.TopUpRequest;
import com.fxbrief.subscription.entity.PlanCode;
import com.fxbrief.subscription.entity.Subscription;
import com.fxbrief.subscription.entity.SubscriptionPlan;
import com.fxbrief.subscription.repository.SubscriptionPlanRepository;
import com.fxbrief.subscription.repository.SubscriptionRepository;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Owns user-initiated top-up: validates the target plan and beta gate, prices the charge
 * in IDR from the current USD/IDR rate, creates a Midtrans Snap transaction, records a
 * {@code PENDING} payment row, and returns the Snap token plus the carry-over preview.
 *
 * <p>No subscription state is changed here — the plan change and report credit happen
 * only when a verified successful webhook is processed (see {@link PaymentWebhookService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final UserRepository userRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final ExchangeRateService exchangeRateService;
    private final MidtransClient midtransClient;
    private final MidtransProperties midtransProperties;

    @Transactional
    public SnapTransactionView initiateTopUp(Long userId, TopUpRequest request) {
        PlanCode targetCode = parseTopUpPlan(request.plan());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.USER_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "User not found"));

        enforceBetaAccess(user);

        Subscription subscription = subscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Subscription not found"));

        SubscriptionPlan targetPlan = planRepository.getReferenceById(targetCode.getId());

        int remaining = subscription.getRemainingReports();
        int additional = targetPlan.getReportCount();
        int newTotal = remaining + additional;
        boolean warningFlag = remaining > 0;

        ExchangeRate rate = exchangeRateService.requireCurrentRate();
        BigDecimal amountUsd = targetPlan.getPrice();
        long amountIdr = toWholeRupiah(amountUsd, rate.getUsdToIdr());

        String orderId = "FXBRIEF-" + userId + "-" + System.currentTimeMillis();
        String itemName = targetPlan.getName() + " plan top-up (20 reports)";

        MidtransClient.SnapToken snapToken = midtransClient.createSnapTransaction(
                orderId, amountIdr, itemName, user.getEmail());

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setUserId(userId);
        transaction.setMidtransOrderId(orderId);
        transaction.setPlan(targetCode.getCode());
        transaction.setAmountUsd(amountUsd);
        transaction.setAmountIdr(amountIdr);
        transaction.setExchangeRateUsed(rate.getUsdToIdr());
        transaction.setStatus(PaymentStatus.PENDING);
        transactionRepository.save(transaction);

        log.info("Top-up initiated: userId={} orderId={} plan={} amountUsd={} amountIdr={} rate={}",
                userId, orderId, targetCode.getCode(), amountUsd, amountIdr, rate.getUsdToIdr());

        return new SnapTransactionView(
                toPlanView(targetPlan),
                orderId,
                snapToken.token(),
                midtransProperties.clientKey(),
                midtransProperties.production(),
                warningFlag,
                rate.getUsdToIdr(),
                amountIdr,
                new CarryOverView(remaining, additional, newTotal));
    }

    /**
     * During the sandbox/testing phase, only whitelisted users may pay. In production
     * (MIDTRANS_IS_PRODUCTION=true) the gate is open to everyone.
     */
    private void enforceBetaAccess(User user) {
        if (midtransProperties.production()) {
            return;
        }
        if (!user.isPaymentBetaAccess()) {
            throw new DomainException(
                    ErrorCodes.PAYMENT_NOT_AVAILABLE,
                    HttpStatus.FORBIDDEN,
                    "Payment is not yet available. Please wait for the full launch.");
        }
    }

    /**
     * Converts a USD price to a whole-rupiah gross amount. Midtrans requires integer IDR
     * gross amounts; the value is rounded half-up to the nearest rupiah.
     */
    private long toWholeRupiah(BigDecimal amountUsd, BigDecimal usdToIdr) {
        return amountUsd.multiply(usdToIdr).setScale(0, RoundingMode.HALF_UP).longValueExact();
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
