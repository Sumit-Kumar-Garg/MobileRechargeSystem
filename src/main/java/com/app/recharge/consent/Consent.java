package com.app.recharge.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consents")
public class Consent {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private ConsentPurpose purpose;

    private boolean granted;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Consent() {
        // for JPA
    }

    /** Creates a consent record for one user and purpose. */
    public Consent(UUID userId, ConsentPurpose purpose, boolean granted, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.purpose = purpose;
        this.granted = granted;
        this.updatedAt = now;
    }

    /** Changes the consent to granted or withdrawn and records when. */
    public void update(boolean granted, Instant now) {
        this.granted = granted;
        this.updatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public ConsentPurpose getPurpose() { return purpose; }
    public boolean isGranted() { return granted; }
}
