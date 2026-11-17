package com.app.recharge.notification;

import com.app.recharge.crypto.PiiCrypto;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Channel preferences. Defaults: everything on, German. The email address is encrypted at rest. */
@Service
public class PreferenceService {

    public record Preferences(boolean pushEnabled, boolean smsEnabled, boolean emailEnabled, String locale, String email) {
        /** Preferences for users who never set any: all channels on, German, no email address. */
        static Preferences defaults() {
            return new Preferences(true, true, true, "de-DE", null);
        }
    }

    private final NotificationPreferenceRepository repository;
    private final PiiCrypto crypto;
    private final Clock clock;

    public PreferenceService(NotificationPreferenceRepository repository, PiiCrypto crypto, Clock clock) {
        this.repository = repository;
        this.crypto = crypto;
        this.clock = clock;
    }

    /** The user's preferences with the email decrypted, or the defaults if none were saved. */
    public Preferences get(UUID userId) {
        return repository.findById(userId).map(p -> new Preferences(
                p.isPushEnabled(), p.isSmsEnabled(), p.isEmailEnabled(), p.getLocale(),
                p.getEmailEnc() == null ? null : crypto.decrypt(p.getEmailEnc())))
                .orElseGet(Preferences::defaults);
    }

    /**
     * Creates or replaces the user's preferences, encrypting the email (blank removes it), and returns the saved
     * values.
     */
    public Preferences save(UUID userId, Preferences prefs) {
        byte[] emailEnc = prefs.email() == null || prefs.email().isBlank() ? null : crypto.encrypt(prefs.email());
        NotificationPreference entity = repository.findById(userId).orElse(null);
        if (entity == null) {
            entity = new NotificationPreference(userId, prefs.pushEnabled(), prefs.smsEnabled(),
                    prefs.emailEnabled(), prefs.locale(), emailEnc, clock.instant());
        } else {
            entity.update(prefs.pushEnabled(), prefs.smsEnabled(), prefs.emailEnabled(), prefs.locale(),
                    emailEnc, clock.instant());
        }
        repository.save(entity);
        return get(userId);
    }
}
