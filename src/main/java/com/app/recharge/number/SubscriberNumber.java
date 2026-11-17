package com.app.recharge.number;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriber_numbers")
public class SubscriberNumber {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "msisdn_enc")
    private byte[] msisdnEnc;

    @Column(name = "msisdn_hash")
    private String msisdnHash;

    @Column(name = "msisdn_masked")
    private String msisdnMasked;

    private String market;
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    protected SubscriberNumber() {
        // for JPA
    }

    /**
     * Creates an active linked number. Takes the number already encrypted, hashed and masked; the plain value is never
     * stored.
     */
    public SubscriberNumber(UUID userId, byte[] msisdnEnc, String msisdnHash, String msisdnMasked,
                            String market, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.msisdnEnc = msisdnEnc;
        this.msisdnHash = msisdnHash;
        this.msisdnMasked = msisdnMasked;
        this.market = market;
        this.status = "ACTIVE";
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public byte[] getMsisdnEnc() { return msisdnEnc; }
    public String getMsisdnHash() { return msisdnHash; }
    public String getMsisdnMasked() { return msisdnMasked; }
    public String getMarket() { return market; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
