package com.app.recharge.payment;

import com.app.recharge.order.RechargeOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "psp_ref")
    private String pspRef;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    @Column(name = "amount_minor")
    private int amountMinor;

    private String currency;
    private String reason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Refund() {
        // for JPA
    }

    /** A new PENDING refund of the full payment amount, with reason provisioning_failed. */
    public static Refund create(RechargeOrder order, Payment payment, Instant now) {
        Refund r = new Refund();
        r.id = UUID.randomUUID();
        r.orderId = order.getId();
        r.paymentId = payment.getId();
        r.status = RefundStatus.PENDING;
        r.amountMinor = payment.getAmountMinor();
        r.currency = payment.getCurrency();
        r.reason = "provisioning_failed";
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    /** Records that the provider refunded the money, with its reference. */
    public void markSucceeded(String pspRef, Instant now) {
        this.status = RefundStatus.SUCCEEDED;
        this.pspRef = pspRef;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getPaymentId() { return paymentId; }
    public String getPspRef() { return pspRef; }
    public RefundStatus getStatus() { return status; }
    public int getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
}
