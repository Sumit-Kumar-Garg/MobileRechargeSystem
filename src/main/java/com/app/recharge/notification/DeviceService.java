package com.app.recharge.notification;

import com.app.recharge.crypto.PiiCrypto;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Push token lifecycle. Tokens are encrypted at rest; a user keeps at most app.notification.max-active-devices (default 5) active devices. */
@Service
public class DeviceService {

    private final DeviceTokenRepository repository;
    private final PiiCrypto crypto;
    private final NotificationProperties props;
    private final Clock clock;

    public DeviceService(DeviceTokenRepository repository, PiiCrypto crypto, NotificationProperties props, Clock clock) {
        this.repository = repository;
        this.crypto = crypto;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Registers or refreshes a push token for the user. Stores it encrypted and hashed, reactivates it if known, and
     * deactivates the oldest tokens beyond the active-device limit. Safe under concurrent calls.
     */
    public void register(UUID userId, String platform, String token) {
        String hash = crypto.hash("device:" + token);
        var existing = repository.findByUserIdAndTokenHash(userId, hash);
        if (existing.isPresent()) {
            DeviceToken t = existing.get();
            t.touch(clock.instant());
            repository.save(t);
            return;
        }
        try {
            repository.saveAndFlush(new DeviceToken(userId, platform, hash, crypto.encrypt(token), clock.instant()));
        } catch (DataIntegrityViolationException race) {
            DeviceToken t = repository.findByUserIdAndTokenHash(userId, hash).orElseThrow(() -> race);
            t.touch(clock.instant());
            repository.save(t);
        }
        trim(userId);
    }

    /** Deactivates the user's push token, if registered. Unknown tokens are ignored. */
    public void unregister(UUID userId, String token) {
        repository.findByUserIdAndTokenHash(userId, crypto.hash("device:" + token)).ifPresent(t -> {
            t.deactivate();
            repository.save(t);
        });
    }

    /** Decrypted tokens for sending. Internal use only. */
    public List<String> activeTokens(UUID userId) {
        return repository.findByUserIdAndActiveTrueOrderByLastSeenAtDesc(userId).stream()
                .map(t -> crypto.decrypt(t.getTokenEnc()))
                .toList();
    }

    /** Deactivates the user's least recently seen tokens so at most the configured number stay active. */
    private void trim(UUID userId) {
        List<DeviceToken> active = repository.findByUserIdAndActiveTrueOrderByLastSeenAtDesc(userId);
        for (int i = props.maxActiveDevices(); i < active.size(); i++) {
            DeviceToken oldest = active.get(i);
            oldest.deactivate();
            repository.save(oldest);
        }
    }
}
