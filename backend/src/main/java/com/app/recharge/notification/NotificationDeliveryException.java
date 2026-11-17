package com.app.recharge.notification;

/** Delivery did not succeed; the Kafka retry topics will try the event again later. */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }
}
