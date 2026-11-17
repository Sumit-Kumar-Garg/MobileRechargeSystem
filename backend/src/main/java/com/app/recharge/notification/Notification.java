package com.app.recharge.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One row per (order, event type, channel). The unique constraint is what makes delivery idempotent. */
@Entity
@Table(name = "notifications")
public class Notification {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @Id
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "event_type")
    private String eventType;

    private String channel;
    private String status;
    private int attempts;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
        // for JPA
    }

    /** A new PENDING notification for one order, event type and channel. */
    public static Notification pending(UUID orderId, UUID userId, String eventType, ChannelType channel, Instant now) {
        Notification n = new Notification();
        n.id = UUID.randomUUID();
        n.orderId = orderId;
        n.userId = userId;
        n.eventType = eventType;
        n.channel = channel.name();
        n.status = PENDING;
        n.createdAt = now;
        return n;
    }

    /** Records a successful delivery with the provider's reference and clears any earlier failure. */
    public void markSent(String providerRef, Instant now) {
        this.attempts++;
        this.status = SENT;
        this.providerRef = providerRef;
        this.failureCode = null;
        this.sentAt = now;
    }

    /** Records a failed delivery attempt and its failure code. */
    public void markFailed(String failureCode) {
        this.attempts++;
        this.status = FAILED;
        this.failureCode = failureCode;
    }

    /** Records that the notification was deliberately not sent, and why. */
    public void markSkipped(String reason) {
        this.status = SKIPPED;
        this.failureCode = reason;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public String getEventType() { return eventType; }
    public String getChannel() { return channel; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getProviderRef() { return providerRef; }
    public String getFailureCode() { return failureCode; }
}
