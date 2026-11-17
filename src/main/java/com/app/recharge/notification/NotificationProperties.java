package com.app.recharge.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notification settings (app.notification.*).
 *
 * @param provider         log (development channels) | the name of real channel adapters
 * @param maxSmsPerDay     SMS per user per day (cost cap; push and email are not capped)
 * @param maxActiveDevices push devices kept active per user; the least recently seen beyond this are deactivated
 */
@ConfigurationProperties("app.notification")
public record NotificationProperties(
        @DefaultValue("log") String provider,
        @DefaultValue("5") int maxSmsPerDay,
        @DefaultValue("5") int maxActiveDevices) {

    public NotificationProperties {
        if (maxSmsPerDay < 0) {
            throw new IllegalArgumentException("app.notification.max-sms-per-day must not be negative");
        }
        if (maxActiveDevices < 1) {
            throw new IllegalArgumentException("app.notification.max-active-devices must be at least 1");
        }
    }

    /** The original defaults, for tests. */
    public static NotificationProperties defaults() {
        return new NotificationProperties("log", 5, 5);
    }
}
