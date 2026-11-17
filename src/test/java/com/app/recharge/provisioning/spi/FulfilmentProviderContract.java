package com.app.recharge.provisioning.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CONTRACT TEST KIT for fulfilment systems (one per plan provider). */
public abstract class FulfilmentProviderContract {

    protected abstract OcsClient provider();

    /** A number the provider will activate a plan for. */
    protected String eligibleMsisdn() {
        return "+491701234567";
    }

    private OcsRequest request(String ref) {
        return new OcsRequest(ref, eligibleMsisdn(), "ANY-PLAN", 28);
    }

    @Test
    void servesExactlyOnePlanProvider() {
        assertThat(provider().providerId()).matches("^[a-z0-9-]{2,40}$");
    }

    @Test
    void provisioningTheSameReferenceTwiceActivatesOnce() {
        String ref = UUID.randomUUID().toString();
        OcsResult first = provider().provision(request(ref));
        assertThat(first.status()).isEqualTo(OcsResult.Status.SUCCESS);
        assertThat(provider().provision(request(ref))).isEqualTo(first);
    }

    @Test
    void aCancelledReferenceIsNeverProvisioned() {
        String ref = UUID.randomUUID().toString();
        provider().cancel(ref);
        assertThat(provider().provision(request(ref)).status()).isEqualTo(OcsResult.Status.REJECTED);
    }

    @Test
    void cancelReversesAnEarlierActivationAndCanBeRepeated() {
        String ref = UUID.randomUUID().toString();
        provider().provision(request(ref));
        provider().cancel(ref);
        provider().cancel(ref);
        assertThat(provider().provision(request(ref)).status()).isEqualTo(OcsResult.Status.REJECTED);
    }
}
