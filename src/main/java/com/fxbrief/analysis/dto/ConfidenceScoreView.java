package com.fxbrief.analysis.dto;

import java.util.List;

public record ConfidenceScoreView(
        int score,
        ConfidenceLevel level,
        List<ConfidenceFactor> factors
) {

    public enum ConfidenceLevel { LOW, MEDIUM, HIGH }

    public record ConfidenceFactor(String label, int delta, boolean applied) {}
}
