package com.fxbrief.analysis.repository;

import com.fxbrief.analysis.entity.MarketAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MarketAnalysisRepository extends JpaRepository<MarketAnalysis, Long> {

    Optional<MarketAnalysis> findByFetchId(UUID fetchId);
}
