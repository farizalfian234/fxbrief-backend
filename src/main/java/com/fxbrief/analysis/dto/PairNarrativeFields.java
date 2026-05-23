package com.fxbrief.analysis.dto;

/**
 * The five Claude-generated text fields per pair, as defined in PRD §8.4.
 *
 * <ul>
 *   <li>{@code setupStatus} — 1 line, used in Card Section 2.</li>
 *   <li>{@code shortReasoning} — 1–2 sentences, used in Card Section 4
 *       (the Free/Basic display).</li>
 *   <li>{@code executiveReasoning} — 3–5 sentences, used in Accordion
 *       Section 1.</li>
 *   <li>{@code invalidationNote} — 1 sentence, used in Accordion Section 6.</li>
 *   <li>{@code fundamentalSummary} — 2–3 sentences, used in Accordion
 *       Section 7.</li>
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
