package com.fxbrief.payment.dto;

import com.fxbrief.subscription.dto.CarryOverView;
import com.fxbrief.subscription.dto.PlanView;

import java.math.BigDecimal;

/**
 * Response of {@code POST /subscription/top-up}: the backend creates a Midtrans Snap
 * transaction and returns the {@code snapToken} the frontend uses to open the Snap popup
 * (no redirect).
 *
 * <p>{@code warningFlag} is true when the user still has remaining reports that will carry
 * over. {@code carryOverCalculation} breaks down the resulting report total.
 * {@code exchangeRate} is the USD/IDR rate used to price this charge, surfaced for
 * display. {@code amountIdr} is the whole-rupiah gross amount sent to Midtrans.
 * {@code clientKey} lets the frontend initialise the Snap SDK.
 */
public record SnapTransactionView(
        PlanView targetPlan,
        String orderId,
        String snapToken,
        String clientKey,
        boolean warningFlag,
        BigDecimal exchangeRate,
        long amountIdr,
        CarryOverView carryOverCalculation
) {}
