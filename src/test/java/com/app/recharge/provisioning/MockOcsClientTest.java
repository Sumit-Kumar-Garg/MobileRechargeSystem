package com.app.recharge.provisioning;

import com.app.recharge.provisioning.spi.OcsUnavailableException;
import com.app.recharge.provisioning.spi.OcsResult;
import com.app.recharge.provisioning.spi.OcsRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MockOcsClientTest {

    private final MockOcsClient ocs = new MockOcsClient();

    private static OcsRequest req(String ref, String msisdn) {
        return new OcsRequest(ref, msisdn, "M-28", 28);
    }

    @Test
    void normalNumbersSucceedIdempotently() {
        OcsResult first = ocs.provision(req("o1", "+491701234567"));
        assertThat(first.status()).isEqualTo(OcsResult.Status.SUCCESS);
        assertThat(ocs.provision(req("o1", "+491701234567"))).isEqualTo(first);
    }

    @Test
    void numbersEndingIn0000AreAlwaysUnavailable() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> ocs.provision(req("o2", "+491701230000")))
                    .isInstanceOf(OcsUnavailableException.class);
        }
    }

    @Test
    void numbersEndingIn0001RecoverOnTheThirdAttempt() {
        assertThatThrownBy(() -> ocs.provision(req("o3", "+491701230001"))).isInstanceOf(OcsUnavailableException.class);
        assertThatThrownBy(() -> ocs.provision(req("o3", "+491701230001"))).isInstanceOf(OcsUnavailableException.class);
        assertThat(ocs.provision(req("o3", "+491701230001")).status()).isEqualTo(OcsResult.Status.SUCCESS);
    }

    @Test
    void numbersEndingIn9999AreRejected() {
        OcsResult r = ocs.provision(req("o4", "+491701239999"));
        assertThat(r.status()).isEqualTo(OcsResult.Status.REJECTED);
        assertThat(r.failureCode()).isEqualTo("number_not_eligible");
    }

    @Test
    void aCancelledReferenceCanNeverBeProvisioned() {
        ocs.cancel("o5");
        OcsResult r = ocs.provision(req("o5", "+491701234567"));
        assertThat(r.status()).isEqualTo(OcsResult.Status.REJECTED);
        assertThat(r.failureCode()).isEqualTo("cancelled");
    }
}
