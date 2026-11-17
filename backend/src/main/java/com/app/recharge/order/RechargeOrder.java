package com.app.recharge.order;

import com.app.recharge.catalog.PlanView;
import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Entity;

/**
 * The recharge order aggregate. Price and plan details are snapshotted at creation, so later catalog changes
 * never alter an existing order. All state changes go through {@link #transitionTo}.
 */
@Entity
@Table(name = "orders")
public class RechargeOrder {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "number_id")
    private UUID numberId;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "plan_provider")
    private String planProvider;

    @Column(name = "plan_code")
    private String planCode;

    @Column(name = "plan_name")
    private String planName;

    @Column(name = "amount_minor")
    private int amountMinor;

    private String currency;

    @Column(name = "validity_days")
    private int validityDays;

    @Column(name = "msisdn_masked")
    private String msisdnMasked;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_fingerprint")
    private String requestFingerprint;

    @Column(name = "journey_id")
    private UUID journeyId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "provisioning_attempts")
    private int provisioningAttempts;

    @Version
    private Long version;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected RechargeOrder() {
        // for JPA
    }

    /**
     * Creates a new order in CREATED state, copying the plan's price, name and validity so later catalog changes do not
     * affect it. It expires {@code ttl} after {@code now} if not paid.
     */
    public static RechargeOrder create(UUID userId, UUID numberId, String msisdnMasked, PlanView plan,
                                       String idempotencyKey, String fingerprint, UUID journeyId,
                                       Instant now, Duration ttl) {
        RechargeOrder o = new RechargeOrder();
        o.id = UUID.randomUUID();
        o.userId = userId;
        o.numberId = numberId;
        o.planId = plan.id();
        o.planProvider = plan.provider();
        o.planCode = plan.code();
        o.planName = plan.name();
        o.amountMinor = plan.price().amountMinor();
        o.currency = plan.price().currency();
        o.validityDays = plan.validityDays();
        o.msisdnMasked = msisdnMasked;
        o.status = OrderStatus.CREATED;
        o.idempotencyKey = idempotencyKey;
        o.requestFingerprint = fingerprint;
        o.journeyId = journeyId;
        o.expiresAt = now.plus(ttl);
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    /**
     * Changes the status if the state machine allows it, otherwise throws ORDER_STATE_INVALID. Reaching COMPLETED sets
     * the completion time and clears any earlier failure code.
     */
    public void transitionTo(OrderStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_INVALID,
                    "Order cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = now;
        if (next == OrderStatus.COMPLETED) {
            this.completedAt = now;
            this.failureCode = null;             // a retry that worked leaves no failure behind
        }
    }

    /** Counts one more attempt to activate the recharge. */
    public void incrementProvisioningAttempts() {
        this.provisioningAttempts++;
    }

    /** Package-private on purpose: other modules record a failure through OrderTransitions.applyFailure. */
    void recordFailure(String failureCode) {
        this.failureCode = failureCode;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getNumberId() { return numberId; }
    public UUID getPlanId() { return planId; }
    public String getPlanProvider() { return planProvider; }
    public String getPlanCode() { return planCode; }
    public String getPlanName() { return planName; }
    public int getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public int getValidityDays() { return validityDays; }
    public String getMsisdnMasked() { return msisdnMasked; }
    public OrderStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getJourneyId() { return journeyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getProvisioningAttempts() { return provisioningAttempts; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
