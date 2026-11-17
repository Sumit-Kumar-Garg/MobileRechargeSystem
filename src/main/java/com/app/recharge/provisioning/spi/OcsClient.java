package com.app.recharge.provisioning.spi;

/**
 * CONTRACT between the fulfilment core and the system that activates a plan (one implementation per plan
 * provider). Calls must be idempotent per {@code referenceId}. A technical problem is thrown as
 * {@link OcsUnavailableException} (unknown outcome); a business refusal is returned as a rejected result.
 */
public interface OcsClient {

    String DEFAULT_PROVIDER = "operator";

    /** Id of the plan provider this implementation fulfils for. Must match the id used in the catalog. */
    default String providerId() {
        return DEFAULT_PROVIDER;
    }

    /**
     * Activates the plan on the number. Idempotent per reference id. Returns a rejected result for a business refusal
     * and throws OcsUnavailableException when the outcome is unknown.
     */
    OcsResult provision(OcsRequest request);

    /**
     * Makes sure the reference is not, and never will be, provisioned (reverses it if an earlier attempt went
     * through without us learning about it). Idempotent. Throws {@link OcsUnavailableException} when this
     * cannot be confirmed.
     */
    void cancel(String referenceId);
}
