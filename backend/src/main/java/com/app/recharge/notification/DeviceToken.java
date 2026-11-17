package com.app.recharge.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    private String platform;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "token_enc")
    private byte[] tokenEnc;

    private boolean active;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at")
    private Instant createdAt;

    protected DeviceToken() {
        // for JPA
    }

    /** A new active push token; takes it already hashed and encrypted. */
    public DeviceToken(UUID userId, String platform, String tokenHash, byte[] tokenEnc, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.platform = platform;
        this.tokenHash = tokenHash;
        this.tokenEnc = tokenEnc;
        this.active = true;
        this.lastSeenAt = now;
        this.createdAt = now;
    }

    /** Marks the token as seen now and reactivates it. */
    public void touch(Instant now) {
        this.active = true;
        this.lastSeenAt = now;
    }

    /** Stops sending to this token. */
    public void deactivate() {
        this.active = false;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getPlatform() { return platform; }
    public String getTokenHash() { return tokenHash; }
    public byte[] getTokenEnc() { return tokenEnc; }
    public boolean isActive() { return active; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
