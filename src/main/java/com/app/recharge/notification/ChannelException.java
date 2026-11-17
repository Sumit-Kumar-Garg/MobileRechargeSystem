package com.app.recharge.notification;

/** A channel failed to deliver. The code is a short, safe label (no PII). */
public class ChannelException extends RuntimeException {

    private final String code;

    public ChannelException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Short failure label such as no_device, safe to store and use as a metric tag. */
    public String getCode() {
        return code;
    }
}
