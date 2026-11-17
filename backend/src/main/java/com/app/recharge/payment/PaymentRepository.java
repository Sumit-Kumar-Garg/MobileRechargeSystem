package com.app.recharge.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Owned by the payment module. Other modules go through {@link PaymentService} and {@link RefundService}. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** How many payment attempts the order has had. */
    long countByOrderId(UUID orderId);

    /** The latest attempt of the order with this status. */
    Optional<Payment> findFirstByOrderIdAndStatusOrderByAttemptNoDesc(UUID orderId, PaymentStatus status);

    /** Up to 50 payments in this status created before {@code before}. */
    List<Payment> findTop50ByStatusAndCreatedAtBefore(PaymentStatus status, Instant before);

    /** Reads only the order id, so the payment row is not loaded before the order lock is held. */
    @Query("select p.orderId from Payment p where p.id = :id")
    Optional<UUID> findOrderIdById(@Param("id") UUID id);

    /** Row lock until the surrounding transaction ends. Always take the order lock first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
}
