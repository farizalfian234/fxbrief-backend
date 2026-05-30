package com.fxbrief.content.repository;

import com.fxbrief.content.entity.WeeklySummary;
import com.fxbrief.content.entity.WeeklySummaryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklySummaryRepository extends JpaRepository<WeeklySummary, Long> {

    Page<WeeklySummary> findByStatus(WeeklySummaryStatus status, Pageable pageable);

    Optional<WeeklySummary> findBySlugAndStatus(String slug, WeeklySummaryStatus status);

    boolean existsByWeekStart(LocalDate weekStart);
}
