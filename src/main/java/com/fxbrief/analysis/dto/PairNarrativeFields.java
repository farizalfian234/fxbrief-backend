package com.fxbrief.analysis.dto;

/**
 * The five Claude-generated text fields per pair, as defined in PRD §8.4
 * and tightened by DECISIONS D-089.
 *
 * <ul>
 *   <li>{@code setupStatus} — 1 line, used in Card Section 2.</li>
 *   <li>{@code shortReasoning} — exactly 1 sentence, max 120 chars, no
 *       price levels. Used in Card Section 4 (the Free/Basic display).</li>
 *   <li>{@code executiveReasoning} — exactly 3 short sentences, no price
 *       levels. Used in Accordion Section 1.</li>
 *   <li>{@code invalidationNote} — 1 sentence, used in Accordion Section 6.
 *       May contain a price level (that is the field's whole purpose).</li>
 *   <li>{@code fundamentalSummary} — exactly 2 short sentences. Used in
 *       Accordion Section 7.</li>
 * </ul>
 *
 * Used as the carrier between the narrative services and {@code AnalysisEngine}
 * so that {@code PairAnalysis} construction is the only place the field count
 * is wired up.
 */
public record PairNarrativeFields(
        String setupStatus,
        String shortReasoning,
        String executiveReasoning,
        String invalidationNote,
        String fundamentalSummary
) {

    public static PairNarrativeFields fallback() {
        String placeholder = "Analysis unavailable — see structured data below.";
        return new PairNarrativeFields(placeholder, placeholder, placeholder, placeholder, placeholder);
    }
}
