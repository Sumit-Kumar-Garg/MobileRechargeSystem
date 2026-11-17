package com.app.recharge.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /** The refund of an order, if one was started. */
    Optional<Refund> findByOrderId(UUID orderId);
}
