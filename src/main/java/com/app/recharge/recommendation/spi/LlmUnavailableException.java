package com.app.recharge.recommendation.spi;

/** The model could not be reached, timed out, or returned something that is not a usable answer. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
