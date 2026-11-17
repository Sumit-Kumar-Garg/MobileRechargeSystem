package com.app.recharge.payment.spi;

public record PspResult(Status status, String pspRef, String failureCode) {

    public enum Status { SUCCEEDED, DECLINED }

    /** An approved result with the provider's reference. */
    public static PspResult succeeded(String pspRef) {
        return new PspResult(Status.SUCCEEDED, pspRef, null);
    }

    /** A declined result with the provider's failure code. */
    public static PspResult declined(String failureCode) {
        return new PspResult(Status.DECLINED, null, failureCode);
    }
}
