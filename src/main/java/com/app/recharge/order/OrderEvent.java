package com.app.recharge.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit trail of every state change. */
@Entity
@Table(name = "order_events")
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    private String reason;

    @Column(name = "created_at")
    private Instant createdAt;

    protected OrderEvent() {
        // for JPA
    }

    /** Records one status change; the reason is cut to 200 characters. {@code from} is null for the creation event. */
    public OrderEvent(UUID orderId, OrderStatus from, OrderStatus to, String reason, Instant now) {
        this.orderId = orderId;
        this.fromStatus = from == null ? null : from.name();
        this.toStatus = to.name();
        this.reason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 200));
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
