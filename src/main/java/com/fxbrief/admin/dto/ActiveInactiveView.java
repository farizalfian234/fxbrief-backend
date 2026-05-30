package com.fxbrief.admin.dto;

/**
 * Current active-vs-inactive user split.
 *
 * <p>The schema records only the present value of {@code users.is_active} with
 * no historical snapshots, so a true time series cannot be reconstructed from
 * existing data. This returns the current split; per PRD §9.3 ("data starts
 * accumulating from day one") a historical series would require a dedicated
 * snapshot table introduced when that requirement becomes real.
 */
public record ActiveInactiveView(
        long active,
        long inactive
) {}
