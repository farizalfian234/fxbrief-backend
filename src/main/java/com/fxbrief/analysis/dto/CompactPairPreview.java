package com.fxbrief.analysis.dto;

/**
 * Trimmed pair preview returned in the three "additional market coverage"
 * cards shown to Free and Basic users on the live dashboard.
 *
 * Per PRD §8.2 Section 3, Free and Basic users see exactly three compact
 * previews of non-best pairs:
 * <ul>
 *   <li>1st — pair with the highest confidence score (excluding the best pair)</li>
 *   <li>2nd — pair with the middle confidence score</li>
 *   <li>3rd — pair with the lowest confidence score</li>
 * </ul>
 *
 * Each preview renders only as a "sentiment label": direction + signal state.
 * Example: "Bearish · Awaiting Confirmation", "Bullish · Confirmed",
 * "Ranging · Setup Detected".
 *
 * These previews are part of the "Unlock Premium" upsell on the live
 * dashboard. They are <b>not</b> included in archived history responses —
 * per the project owner's "clean reading experience" rule, the history
 * detail endpoint exposes no upsell affordance.
 */
public record CompactPairPreview(
        String pair,
        MarketStructureView.MarketBias dailyBias,
        SignalState signalState
) {}
