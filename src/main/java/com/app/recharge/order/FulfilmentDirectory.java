package com.app.recharge.order;

/**
 * What the order module needs to know about fulfilment: can a given plan provider's plans be activated?
 * Declared here (by the consumer) and implemented by the provisioning module, so orders do not depend on
 * provisioning internals.
 */
public interface FulfilmentDirectory {
    /**
     * True if a fulfilment system is configured for this plan provider, so its plans can be activated after payment.
     */
    boolean canFulfil(String planProviderId);
}
