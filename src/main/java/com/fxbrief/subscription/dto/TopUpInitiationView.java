package com.fxbrief.subscription.dto;

public record TopUpInitiationView(
        PlanView targetPlan,
        String paymentUrl,
        boolean warningFlag,
        CarryOverView carryOverCalculation
) {}
