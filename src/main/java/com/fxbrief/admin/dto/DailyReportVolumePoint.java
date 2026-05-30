package com.fxbrief.admin.dto;

import java.time.LocalDate;

/**
 * One forex market day of report-generation volume for the dashboard chart.
 */
public record DailyReportVolumePoint(
        LocalDate forexMarketDate,
        long count
) {}
