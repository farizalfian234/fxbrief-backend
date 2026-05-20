package com.fxbrief.subscription.dto;

public record CarryOverView(
        int remainingReports,
        int additionalReports,
        int newTotal
) {}
