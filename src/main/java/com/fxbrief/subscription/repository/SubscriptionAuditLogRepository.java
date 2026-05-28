package com.fxbrief.subscription.repository;

import com.fxbrief.subscription.entity.SubscriptionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionAuditLogRepository extends JpaRepository<SubscriptionAuditLog, Long> {
}
