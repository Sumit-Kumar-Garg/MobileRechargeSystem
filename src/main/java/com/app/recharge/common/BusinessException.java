package com.app.recharge.common;

/** Expected, client-visible failure. The message must be safe to show to the caller (no PII, no internals). */
public class BusinessException extends RuntimeException {
    private final ErrorCode code;

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /** The stable error code sent to the client. */
    public ErrorCode getCode() {
        return code;
    }
}
