package com.app.recharge.notification;

/** The message can never be processed (unparseable). Skips retries and goes straight to the DLT. */
public class PoisonMessageException extends RuntimeException {
    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
