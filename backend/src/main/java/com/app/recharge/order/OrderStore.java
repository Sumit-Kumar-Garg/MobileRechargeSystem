package com.app.recharge.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The order module's API for other modules (payment, provisioning, reconciliation, recommendation,
 * monitoring). They read and lock orders through this class and change status through
 * {@link OrderTransitions}; the repository itself stays inside the order package, so the order tables have
 * one owner. The boundary is checked by {@code ArchitectureTest}.
 */
@Component
public class OrderStore {

    private final OrderRepository repository;

    public OrderStore(OrderRepository repository) {
        this.repository = repository;
    }

    /** An order by id, regardless of owner. For internal use by background processes. */
    public Optional<RechargeOrder> findById(UUID orderId) {
        return repository.findById(orderId);
    }

    /** Empty when the order does not exist OR belongs to someone else: callers cannot tell the difference. */
    public Optional<RechargeOrder> findByIdAndUserId(UUID orderId, UUID userId) {
        return repository.findByIdAndUserId(orderId, userId);
    }

    /** Row lock until the surrounding transaction ends. Must be called inside a transaction. */
    public Optional<RechargeOrder> findByIdForUpdate(UUID orderId) {
        return repository.findByIdForUpdate(orderId);
    }

    /** Up to 50 orders in this status that have not changed since {@code updatedBefore}, oldest first. */
    public List<RechargeOrder> oldestInStatus(OrderStatus status, Instant updatedBefore) {
        return repository.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(status, updatedBefore);
    }

    /** Up to 100 orders in these statuses whose payment deadline has passed. */
    public List<RechargeOrder> expiredIn(Collection<OrderStatus> statuses, Instant now) {
        return repository.findTop100ByStatusInAndExpiresAtBefore(statuses, now);
    }

    /** The ten most recent orders of a number in this status, newest first. */
    public List<RechargeOrder> recentByNumber(UUID numberId, OrderStatus status) {
        return repository.findTop10ByNumberIdAndStatusOrderByCompletedAtDesc(numberId, status);
    }

    /**
     * How many orders are in these statuses and unchanged since {@code updatedBefore} (used for the stuck-orders
     * alert).
     */
    public long countInStatusesOlderThan(Collection<OrderStatus> statuses, Instant updatedBefore) {
        return repository.countByStatusInAndUpdatedAtBefore(statuses, updatedBefore);
    }
}
