package com.app.recharge.notification;

/** Port for one delivery channel. FCM, an SMS gateway and an email provider each implement it. */
public interface NotificationChannel {

    /** Which channel this implementation delivers to. */
    ChannelType type();

    /** @return provider reference for tracing; throws {@link ChannelException} on failure */
    String send(Recipient recipient, NotificationContent content);
}
