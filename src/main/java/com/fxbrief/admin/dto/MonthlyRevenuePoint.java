package com.fxbrief.admin.dto;

import java.math.BigDecimal;

/**
 * One month of revenue for the dashboard chart. {@code month} is the forex
 * market month in {@code YYYY-MM} form; {@code revenue} is the summed USD
 * value of paid top-up events whose forex market date falls in that month.
 */
public record MonthlyRevenuePoint(
        String month,
        BigDecimal revenue
) {}
