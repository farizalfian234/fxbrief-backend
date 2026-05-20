package com.fxbrief.subscription.repository;

import com.fxbrief.subscription.entity.SubscriptionUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface SubscriptionUsageRepository extends JpaRepository<SubscriptionUsage, Long> {

    boolean existsByUserIdAndForexMarketDate(Long userId, LocalDate forexMarketDate);
}
