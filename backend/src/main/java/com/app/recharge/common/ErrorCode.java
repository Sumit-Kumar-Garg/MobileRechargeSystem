package com.app.recharge.common;

import org.springframework.http.HttpStatus;

/** Stable, client-facing error codes. Add new codes per slice; never rename existing ones. */
public enum ErrorCode {
    REQUEST_INVALID(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_STATE_INVALID(HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    PLAN_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYMENT_METHOD_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    NUMBER_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    PAYMENT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    /** The HTTP status returned to the client for this error. */
    public HttpStatus getStatus() {
        return status;
    }
}
