package com.app.recharge.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    /** A user's device by the keyed hash of its push token. */
    Optional<DeviceToken> findByUserIdAndTokenHash(UUID userId, String tokenHash);

    /** The user's active devices, most recently seen first. */
    List<DeviceToken> findByUserIdAndActiveTrueOrderByLastSeenAtDesc(UUID userId);
}
