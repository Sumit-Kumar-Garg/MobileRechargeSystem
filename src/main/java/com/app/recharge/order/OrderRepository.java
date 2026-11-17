package com.app.recharge.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<RechargeOrder, UUID> {

    /** An order by id, only if it belongs to the user. */
    Optional<RechargeOrder> findByIdAndUserId(UUID id, UUID userId);

    /** The order a user created with this Idempotency-Key, if any. */
    Optional<RechargeOrder> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /** Loads an order with a row lock (SELECT ... FOR UPDATE) held until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RechargeOrder o where o.id = :id")
    Optional<RechargeOrder> findByIdForUpdate(@Param("id") UUID id);

    /** A user's 20 newest orders. */
    List<RechargeOrder> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);

    /** The 10 most recently completed orders of a number in this status. */
    List<RechargeOrder> findTop10ByNumberIdAndStatusOrderByCompletedAtDesc(UUID numberId, OrderStatus status);

    /** Up to 50 orders in this status not updated since {@code before}, oldest first. */
    List<RechargeOrder> findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(OrderStatus status, Instant before);

    /** Up to 100 orders in these statuses whose expiry time has passed. */
    List<RechargeOrder> findTop100ByStatusInAndExpiresAtBefore(Collection<OrderStatus> statuses, Instant before);

    /** How many orders are in these statuses and unchanged since {@code before}. */
    long countByStatusInAndUpdatedAtBefore(Collection<OrderStatus> statuses, Instant before);
}
