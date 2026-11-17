package com.app.recharge.provisioning.spi;

public record OcsResult(Status status, String ocsRef, String failureCode) {

    public enum Status { SUCCESS, REJECTED }

    /** A successful activation with the billing system's reference. */
    public static OcsResult success(String ocsRef) {
        return new OcsResult(Status.SUCCESS, ocsRef, null);
    }

    /** A definitive refusal with its failure code; retrying will not help. */
    public static OcsResult rejected(String failureCode) {
        return new OcsResult(Status.REJECTED, null, failureCode);
    }
}
