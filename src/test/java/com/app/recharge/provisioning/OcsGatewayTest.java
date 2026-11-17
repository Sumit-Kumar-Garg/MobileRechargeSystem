package com.app.recharge.provisioning;

import com.app.recharge.provisioning.spi.OcsUnavailableException;
import com.app.recharge.provisioning.spi.OcsResult;
import com.app.recharge.provisioning.spi.OcsRequest;
import com.app.recharge.provisioning.spi.OcsClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.common.CircuitBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OcsGatewayTest {

    private static class DownOcs implements OcsClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override public OcsResult provision(OcsRequest request) {
            calls.incrementAndGet();
            throw new OcsUnavailableException("down");
        }

        @Override public void cancel(String referenceId) {
            calls.incrementAndGet();
            throw new OcsUnavailableException("down");
        }
    }

    private static OcsRequest request() {
        return new OcsRequest("ref", "+491701234567", "M-28", 28);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingTheBillingSystem() {
        DownOcs ocs = new DownOcs();
        CircuitBreaker breaker = CircuitBreakers.create("test-ocs", OcsUnavailableException.class, 4, 4);
        OcsGateway gateway = new OcsGateway(ocs, breaker, 10, Duration.ofSeconds(1));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gateway.provision("operator", request())).isInstanceOf(OcsUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> gateway.provision("operator", request()))
                .isInstanceOf(OcsUnavailableException.class).hasMessageContaining("circuit");
        assertThatThrownBy(() -> gateway.cancel("operator", "ref")).isInstanceOf(OcsUnavailableException.class);
        assertThat(ocs.calls.get()).isEqualTo(4);
    }

    @Test
    void aHangingBillingSystemIsCutOffByTheTimeout() {
        OcsClient hanging = new OcsClient() {
            @Override public OcsResult provision(OcsRequest request) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return OcsResult.success("late");
            }

            @Override public void cancel(String referenceId) { }
        };
        OcsGateway gateway = new OcsGateway(hanging,
                CircuitBreakers.create("slow-ocs", OcsUnavailableException.class), 10, Duration.ofMillis(100));

        assertThatThrownBy(() -> gateway.provision("operator", request()))
                .isInstanceOf(OcsUnavailableException.class).hasMessageContaining("timed out");
    }

    @Test
    void aPlanProviderWithoutAFulfilmentSystemIsADefinitiveRejection() {
        OcsGateway gateway = new OcsGateway(new DownOcs(),
                CircuitBreakers.create("reg-ocs", OcsUnavailableException.class), 10, Duration.ofSeconds(1));

        assertThat(gateway.canFulfil("operator")).isTrue();
        assertThat(gateway.canFulfil("unknown-partner")).isFalse();
        OcsResult result = gateway.provision("unknown-partner", request());
        assertThat(result.status()).isEqualTo(OcsResult.Status.REJECTED);
        assertThat(result.failureCode()).isEqualTo("fulfilment_provider_unknown");
        gateway.cancel("unknown-partner", "ref");            // nothing to cancel, must not throw
    }

    @Test
    void aDeploymentWithoutAnyFulfilmentSystemDoesNotStart() {
        assertThatThrownBy(() -> new OcsGateway(java.util.List.of(), 30, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No fulfilment system");
    }
}
