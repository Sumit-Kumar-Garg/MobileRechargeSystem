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
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "attempt_no")
    private int attemptNo;

    /** Which provider handled this attempt. Every follow-up (lookup, cancel, refund) goes to the same one. */
    private String provider;

    private String method;

    @Column(name = "psp_ref")
    private String pspRef;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "amount_minor")
    private int amountMinor;

    private String currency;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    /** A new PENDING payment attempt for the order, for its full amount, at the given provider and method. */
    public static Payment create(RechargeOrder order, int attemptNo, String provider, String method, Instant now) {
        Payment p = new Payment();
        p.provider = provider;
        p.method = method;
        p.id = UUID.randomUUID();
        p.orderId = order.getId();
        p.attemptNo = attemptNo;
        p.status = PaymentStatus.PENDING;
        p.amountMinor = order.getAmountMinor();
        p.currency = order.getCurrency();
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /** Records that the provider took the money, with its reference. */
    public void markSucceeded(String pspRef, Instant now) {
        this.status = PaymentStatus.SUCCEEDED;
        this.pspRef = pspRef;
        this.updatedAt = now;
    }

    /** Records a declined payment and its failure code. */
    public void markFailed(String failureCode, Instant now) {
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public int getAttemptNo() { return attemptNo; }
    public String getProvider() { return provider; }
    public String getMethod() { return method; }
    public String getPspRef() { return pspRef; }
    public PaymentStatus getStatus() { return status; }
    public int getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
