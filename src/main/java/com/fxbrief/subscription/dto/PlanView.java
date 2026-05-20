package com.fxbrief.subscription.dto;

import java.math.BigDecimal;

public record PlanView(
        Short id,
        String name,
        BigDecimal price,
        Integer reportQuota
) {}
