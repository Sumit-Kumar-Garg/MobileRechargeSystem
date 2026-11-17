package com.app.recharge.payment.spi;

/** The outcome of the call is UNKNOWN (timeout, 5xx, open circuit). Never treat this as a decline. */
public class PspUnavailableException extends RuntimeException {
    public PspUnavailableException(String message) {
        super(message);
    }
}
