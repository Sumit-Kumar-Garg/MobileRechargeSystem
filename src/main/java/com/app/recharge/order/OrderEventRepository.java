package com.app.recharge.order;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

    /** The audit trail of one order, in the order the changes happened. */
    List<OrderEvent> findByOrderIdOrderByIdAsc(UUID orderId);
}
