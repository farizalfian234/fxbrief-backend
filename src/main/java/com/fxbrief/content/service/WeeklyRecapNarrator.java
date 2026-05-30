package com.fxbrief.content.service;

import com.fxbrief.analysis.client.ClaudeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Turns the public-safe weekly aggregate into a readable recap narrative via
 * the existing Claude client. The prompt forbids any trade-secret content
 * (entry/SL/TP, exact confidence scores) so the output is safe for the public
 * recap page even before admin review.
 */
@Service
@RequiredArgsConstructor
public class WeeklyRecapNarrator {

    private final ClaudeClient claudeClient;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private static final String SYSTEM_PROMPT = """
            You are a forex market analyst writing a weekly market recap for a public audience.
            Write a clear, professional narrative suitable for readers who are not subscribers.

            Strict rules:
            - This is a high-level recap. Do NOT include any trade entry prices, stop-loss or
              take-profit levels, or exact numeric confidence scores.
            - Describe only: the most active currency pairs, the dominant market bias direction,
              major macroeconomic drivers, significant market structure shifts, and which pairs
              were strongest or weakest in qualitative terms.
            - Base everything on the structured data provided. Do not invent events, prices, or
              figures that are not present in the data.
            - Write in flowing prose with short sections. Do not output JSON, tables, or code.
            - Keep it concise and readable: roughly 300 to 500 words.""";

    public String generate(LocalDate weekStart, LocalDate weekEnd, String aggregate) {
        String userPrompt = """
                Write the weekly forex market recap for the week of %s to %s.

                Structured market data aggregated from the week's daily analysis (public-safe
                signals only):

                %s

                Now write the recap narrative following all the rules in your instructions."""
                .formatted(DATE.format(weekStart), DATE.format(weekEnd), aggregate);

        return claudeClient.generateNarrative(SYSTEM_PROMPT, userPrompt);
    }
}
