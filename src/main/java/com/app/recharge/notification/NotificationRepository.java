package com.app.recharge.notification;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** The notification row for one order, event type and channel, if one was claimed. */
    Optional<Notification> findByOrderIdAndEventTypeAndChannel(UUID orderId, String eventType, String channel);

    /** True if a notification for this order and event already has the given status on any of the given channels. */
    boolean existsByOrderIdAndEventTypeAndChannelInAndStatus(
            UUID orderId, String eventType, Collection<String> channels, String status);
}
