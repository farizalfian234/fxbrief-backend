package com.fxbrief.report.repository;

import com.fxbrief.report.entity.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface UserReportRepository extends JpaRepository<UserReport, Long> {

    Optional<UserReport> findByUserIdAndForexMarketDate(Long userId, LocalDate forexMarketDate);

    /**
     * Bulk archive every non-archived row whose forex market date is on or before the
     * supplied cutoff. Used by the daily 22:00 UTC scheduler — once a forex market day
     * rolls over, every report belonging to that day or earlier is frozen.
     */
    @Modifying
    @Query("UPDATE UserReport r SET r.archived = true, r.archivedAt = :archivedAt " +
           "WHERE r.archived = false AND r.forexMarketDate <= :cutoff")
    int archiveOnOrBefore(@Param("cutoff") LocalDate cutoff, @Param("archivedAt") Instant archivedAt);
}
