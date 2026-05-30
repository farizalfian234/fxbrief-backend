package com.fxbrief.admin.dto;

/**
 * One month of a counted metric (e.g. new subscribers). {@code month} is the
 * forex market month in {@code YYYY-MM} form.
 */
public record MonthlyCountPoint(
        String month,
        long count
) {}
