package com.app.recharge.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "push_enabled")
    private boolean pushEnabled;

    @Column(name = "sms_enabled")
    private boolean smsEnabled;

    @Column(name = "email_enabled")
    private boolean emailEnabled;

    private String locale;

    @Column(name = "email_enc")
    private byte[] emailEnc;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected NotificationPreference() {
        // for JPA
    }

    /** Creates a user's preferences; takes the email address already encrypted. */
    public NotificationPreference(UUID userId, boolean push, boolean sms, boolean email, String locale,
                                  byte[] emailEnc, Instant now) {
        this.userId = userId;
        update(push, sms, email, locale, emailEnc, now);
    }

    /** Replaces all preferences and records when they changed. */
    public void update(boolean push, boolean sms, boolean email, String locale, byte[] emailEnc, Instant now) {
        this.pushEnabled = push;
        this.smsEnabled = sms;
        this.emailEnabled = email;
        this.locale = locale;
        this.emailEnc = emailEnc;
        this.updatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public boolean isPushEnabled() { return pushEnabled; }
    public boolean isSmsEnabled() { return smsEnabled; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public String getLocale() { return locale; }
    public byte[] getEmailEnc() { return emailEnc; }
}
