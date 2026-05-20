package com.fxbrief.subscription.dto;

public record SubscriptionView(
        PlanView plan,
        PlanView effectivePlan,
        int remainingReports,
        boolean hasEverPaid,
        boolean marketOpen
) {}
