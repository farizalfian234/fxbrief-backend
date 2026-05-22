package com.fxbrief.analysis.service;

import com.fxbrief.analysis.config.AnalysisProperties;
import com.fxbrief.analysis.dto.ConfidenceScoreView;
import com.fxbrief.analysis.dto.ConfidenceScoreView.ConfidenceFactor;
import com.fxbrief.analysis.dto.ConfidenceScoreView.ConfidenceLevel;
import com.fxbrief.analysis.dto.FundamentalAssessment;
import com.fxbrief.analysis.dto.M15ConfirmationView;
import com.fxbrief.analysis.dto.MarketStructureView;
import com.fxbrief.analysis.dto.Zone;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ConfidenceScorer {

    private final AnalysisProperties properties;

    public ConfidenceScorer(AnalysisProperties properties) {
        this.properties = properties;
    }

    public ConfidenceScoreView score(MarketStructureView weekly,
                                     MarketStructureView daily,
                                     Zone zone,
                                     M15ConfirmationView m15,
                                     FundamentalAssessment baseFundamental,
                                     FundamentalAssessment quoteFundamental,
                                     boolean candlestickPatternPresent) {
        AnalysisProperties.ConfidenceWeights w = properties.confidenceWeights();
        AnalysisProperties.ZoneFreshness fresh = properties.zoneFreshness();
        List<ConfidenceFactor> factors = new ArrayList<>();
        int total = 0;

        boolean htfAligned = weekly != null && daily != null
                && weekly.bias() != MarketStructureView.MarketBias.RANGING
                && weekly.bias() == daily.bias();
        total += addFactor(factors, "HTF structure alignment (W + D)", w.htfStructureAligned(), htfAligned);

        boolean freshZone = zone != null
                && !zone.invalidated()
                && zone.freshnessScore() >= fresh.neverRetestedScore();
        total += addFactor(factors, "Fresh zone (never retested)", w.freshZone(), freshZone);

        boolean m15Bos = m15 != null && m15.m15Bos();
        total += addFactor(factors, "M15 BOS confirmation", w.m15Bos(), m15Bos);

        boolean sweep = m15 != null && m15.liquiditySweep();
        total += addFactor(factors, "Liquidity sweep present", w.liquiditySweep(), sweep);

        boolean fibConfluence = zone != null
                && zone.fibonacciLevelsInside() != null
                && !zone.fibonacciLevelsInside().isEmpty();
        total += addFactor(factors, "Fibonacci confluence inside zone", w.fibonacciConfluence(), fibConfluence);

        total += addFactor(factors, "Candlestick pattern confirmation", w.candlestickPattern(), candlestickPatternPresent);

        boolean fundamentalConflict = hasFundamentalConflict(baseFundamental, quoteFundamental);
        total += addFactor(factors, "Fundamental conflict this week", w.fundamentalConflict(), fundamentalConflict);

        boolean deeplyPenetratedZone = zone != null
                && !zone.invalidated()
                && zone.penetrationDepth() == Zone.PenetrationDepth.DEEP;
        total += addFactor(factors, "Zone deeply penetrated",
                w.deeplyPenetratedZone(), deeplyPenetratedZone);

        return new ConfidenceScoreView(total, levelOf(total, w), factors);
    }

    private int addFactor(List<ConfidenceFactor> factors, String label, int delta, boolean applied) {
        factors.add(new ConfidenceFactor(label, delta, applied));
        return applied ? delta : 0;
    }

    private boolean hasFundamentalConflict(FundamentalAssessment a, FundamentalAssessment b) {
        if (a != null && a.highImpactThisWeek()) {
            return true;
        }
        return b != null && b.highImpactThisWeek();
    }

    private ConfidenceLevel levelOf(int total, AnalysisProperties.ConfidenceWeights w) {
        if (total <= w.lowMaxScore()) {
            return ConfidenceLevel.LOW;
        }
        if (total <= w.mediumMaxScore()) {
            return ConfidenceLevel.MEDIUM;
        }
        return ConfidenceLevel.HIGH;
    }
}
