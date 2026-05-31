package com.fxbrief.payment.repository;

import com.fxbrief.payment.entity.PaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByMidtransOrderId(String midtransOrderId);

    /**
     * Locks the transaction row for the duration of webhook processing so two concurrent
     * deliveries of the same notification cannot both apply the credit. The second waiter
     * observes the already-PAID status and treats its delivery as a duplicate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PaymentTransaction t WHERE t.midtransOrderId = :orderId")
    Optional<PaymentTransaction> findByMidtransOrderIdForUpdate(@Param("orderId") String orderId);
}
