package com.app.recharge.notification;

import com.app.recharge.common.RechargeMetrics;
import com.app.recharge.number.NumberService;
import com.app.recharge.order.OrderEventPayload;
import com.app.recharge.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Turns a domain event into notifications. Push first, SMS as fallback when push is unavailable or fails,
 * email as an independent receipt. Safe to run twice for the same event: each (order, event, channel)
 * is claimed once and a SENT row is never sent again. Throws when nothing reached the user, so Kafka retries.
 */
@Service
public class NotificationService {

    static final Set<String> NOTIFIABLE = Set.of("OrderCompleted", "OrderRefunded");

    private enum Outcome { DELIVERED, FAILED }

    private final NotificationRepository notifications;
    private final PreferenceService preferences;
    private final DeviceService devices;
    private final NumberService numbers;
    private final MessageRenderer renderer;
    private final RateLimiter limiter;
    private final NotificationProperties props;
    private final RechargeMetrics metrics;
    private final Clock clock;
    private final Map<ChannelType, NotificationChannel> channels = new EnumMap<>(ChannelType.class);

    public NotificationService(NotificationRepository notifications, PreferenceService preferences,
                               DeviceService devices, NumberService numbers, MessageRenderer renderer,
                               RateLimiter limiter, RechargeMetrics metrics, NotificationProperties props,
                               Clock clock, List<NotificationChannel> channelList) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.devices = devices;
        this.numbers = numbers;
        this.renderer = renderer;
        this.limiter = limiter;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        channelList.forEach(c -> channels.put(c.type(), c));
    }

    /**
     * Notifies the user about a completed or refunded order (other events are ignored). Tries push, falls back to SMS
     * (at most app.notification.max-sms-per-day), and sends email independently, following the user's preferences and skipping channels
     * already delivered. Throws NotificationDeliveryException if a channel still needs a retry.
     */
    public void handle(OrderEventPayload event) {
        if (!NOTIFIABLE.contains(event.type())) {
            return;
        }
        PreferenceService.Preferences prefs = preferences.get(event.userId());
        NotificationContent content = renderer.render(event, Locale.forLanguageTag(prefs.locale()));

        boolean primaryDelivered = notifications.existsByOrderIdAndEventTypeAndChannelInAndStatus(
                event.orderId(), event.type(), List.of(ChannelType.PUSH.name(), ChannelType.SMS.name()), Notification.SENT);
        boolean primaryAttempted = primaryDelivered;
        boolean capBlocked = false;
        boolean needsRetry = false;

        if (!primaryDelivered && prefs.pushEnabled()) {
            List<String> tokens = devices.activeTokens(event.userId());
            if (!tokens.isEmpty()) {
                primaryAttempted = true;
                primaryDelivered = deliver(ChannelType.PUSH, event,
                        new Recipient(event.userId(), null, null, tokens), content) == Outcome.DELIVERED;
            }
        }
        if (!primaryDelivered && prefs.smsEnabled()) {
            if (limiter.hit("sms:" + event.userId(), props.maxSmsPerDay(), Duration.ofDays(1)).allowed()) {
                primaryAttempted = true;
                Recipient r = new Recipient(event.userId(), numbers.plainMsisdn(event.numberId()), null, List.of());
                primaryDelivered = deliver(ChannelType.SMS, event, r, content) == Outcome.DELIVERED;
            } else {
                capBlocked = true;
                metrics.notification(ChannelType.SMS.name(), "capped");
            }
        }
        if (prefs.emailEnabled() && prefs.email() != null) {
            Recipient r = new Recipient(event.userId(), null, prefs.email(), List.of());
            if (deliver(ChannelType.EMAIL, event, r, content) == Outcome.FAILED) {
                needsRetry = true;
            }
        }
        if (primaryAttempted && !primaryDelivered && !capBlocked) {
            needsRetry = true;
        }
        if (needsRetry) {
            throw new NotificationDeliveryException("Notification for order " + event.orderId() + " not delivered yet");
        }
    }

    /**
     * Sends through one channel unless this order, event and channel was already sent, and records the result on the
     * notification row.
     */
    private Outcome deliver(ChannelType type, OrderEventPayload event, Recipient recipient, NotificationContent content) {
        Notification row = claim(event, type);
        if (Notification.SENT.equals(row.getStatus())) {
            return Outcome.DELIVERED;
        }
        try {
            String ref = channels.get(type).send(recipient, content);
            row.markSent(ref, clock.instant());
            notifications.save(row);
            metrics.notification(type.name(), "sent");
            return Outcome.DELIVERED;
        } catch (ChannelException e) {
            row.markFailed(e.getCode());
            notifications.save(row);
            metrics.notification(type.name(), "failed");
            return Outcome.FAILED;
        }
    }

    /**
     * Gets or creates the notification row for this order, event and channel. Safe under concurrent consumers: the
     * unique constraint picks one row.
     */
    private Notification claim(OrderEventPayload event, ChannelType type) {
        return notifications.findByOrderIdAndEventTypeAndChannel(event.orderId(), event.type(), type.name())
                .orElseGet(() -> {
                    try {
                        return notifications.saveAndFlush(Notification.pending(
                                event.orderId(), event.userId(), event.type(), type, clock.instant()));
                    } catch (DataIntegrityViolationException race) {
                        return notifications.findByOrderIdAndEventTypeAndChannel(
                                event.orderId(), event.type(), type.name()).orElseThrow(() -> race);
                    }
                });
    }
}
