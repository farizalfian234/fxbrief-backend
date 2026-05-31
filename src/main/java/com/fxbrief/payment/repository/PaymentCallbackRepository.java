package com.fxbrief.payment.repository;

import com.fxbrief.payment.entity.PaymentCallback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentCallbackRepository extends JpaRepository<PaymentCallback, Long> {
}
