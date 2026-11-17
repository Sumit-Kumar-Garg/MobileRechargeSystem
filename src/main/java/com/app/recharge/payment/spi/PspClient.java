package com.app.recharge.payment.spi;

import java.util.Optional;
import java.util.Set;

/**
 * CONTRACT between the payments core and a payment-provider adapter (one implementation per provider).
 * A provider team implements this interface in its own package, depends on nothing but this {@code spi}
 * package, and proves the adapter with the shared contract test ({@code PaymentProviderContract}).
 *
 * Rules every implementation must keep:
 * - charge, refund and cancel are idempotent per merchant reference;
 * - a technical problem (timeout, 5xx, network) is thrown as {@link PspUnavailableException}, meaning the
 *   outcome is unknown, and is never reported as a decline;
 * - cancel makes a reference unusable, so a charge that is still in flight cannot succeed afterwards.
 */
public interface PspClient {

    /** Stable provider id, lower case, for example "mockpay". Stored on every payment. Never change it. */
    String id();

    /** Payment methods this provider offers, for example "card", "wallet". */
    Set<String> methods();

    /** Whether this provider can take a payment in this currency with this method. */
    boolean supports(String currency, String method);

    /**
     * Charges the customer. Idempotent per merchant reference. Throws PspUnavailableException when the outcome is
     * unknown.
     */
    PspResult charge(PspChargeRequest request);

    /** Refunds an earlier charge. Idempotent per refund merchant reference. */
    PspResult refund(PspRefundRequest request);

    /** What the provider knows about a merchant reference (used to resolve unknown outcomes). */
    Optional<PspResult> lookup(String merchantRef);

    /**
     * Voids a merchant reference. Returns the final state of the reference: SUCCEEDED if the charge had
     * already gone through, DECLINED otherwise.
     */
    PspResult cancel(String merchantRef);
}
