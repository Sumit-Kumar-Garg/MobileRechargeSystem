package com.app.recharge.payment;

import com.app.recharge.payment.spi.PspUnavailableException;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspRefundRequest;
import com.app.recharge.payment.spi.PspChargeRequest;
import com.app.recharge.payment.spi.PspClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.recharge.common.CircuitBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PspGatewayTest {

    private static class FlakyPsp implements PspClient {
        final AtomicInteger calls = new AtomicInteger();
        boolean failing = true;

        @Override public String id() { return "flaky"; }

        @Override public java.util.Set<String> methods() { return java.util.Set.of("card"); }

        @Override public boolean supports(String currency, String method) { return true; }

        @Override public PspResult charge(PspChargeRequest r) {
            calls.incrementAndGet();
            if (failing) {
                throw new PspUnavailableException("down");
            }
            return PspResult.succeeded("ok");
        }

        @Override public PspResult refund(PspRefundRequest r) { return PspResult.succeeded("r"); }

        @Override public Optional<PspResult> lookup(String ref) { return Optional.empty(); }

        @Override public PspResult cancel(String ref) { return PspResult.declined("cancelled"); }
    }

    private static PspChargeRequest request() {
        return new PspChargeRequest("ref", 1000, "EUR", "tok");
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingThePsp() {
        FlakyPsp psp = new FlakyPsp();
        CircuitBreaker breaker = CircuitBreakers.create("test-psp", PspUnavailableException.class, 4, 4);
        PspGateway gateway = new PspGateway(psp, breaker);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gateway.charge("flaky", request())).isInstanceOf(PspUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> gateway.charge("flaky", request()))
                .isInstanceOf(PspUnavailableException.class)
                .hasMessageContaining("circuit");
        assertThat(psp.calls.get()).isEqualTo(4);     // the 5th call never reached the PSP
    }

    @Test
    void aProviderWithAnOpenCircuitIsReportedAsNotAvailableForRouting() {
        FlakyPsp psp = new FlakyPsp();
        PspGateway gateway = new PspGateway(psp, CircuitBreakers.create("route-psp", PspUnavailableException.class, 4, 4));
        assertThat(gateway.isAvailable("flaky")).isTrue();

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> gateway.charge("flaky", request())).isInstanceOf(PspUnavailableException.class);
        }

        assertThat(gateway.isAvailable("flaky")).isFalse();
        assertThat(gateway.isAvailable("someone-else")).isFalse();
    }

    @Test
    void aProviderThatIsNotConfiguredIsAnUnknownOutcomeNotADecline() {
        PspGateway gateway = new PspGateway(new FlakyPsp(), CircuitBreakers.create("x-psp", PspUnavailableException.class));
        assertThatThrownBy(() -> gateway.lookup("removed-provider", "ref")).isInstanceOf(PspUnavailableException.class);
    }

    @Test
    void healthyPspPassesThrough() {
        FlakyPsp psp = new FlakyPsp();
        psp.failing = false;
        PspGateway gateway = new PspGateway(psp, CircuitBreakers.create("ok-psp", PspUnavailableException.class));

        assertThat(gateway.charge("flaky", request()).status()).isEqualTo(PspResult.Status.SUCCEEDED);
    }

    @Test
    void aDeploymentWithoutAnyPaymentProviderDoesNotStart() {
        assertThatThrownBy(() -> new PspGateway(java.util.List.of(), 50, java.time.Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No payment provider");
    }
}
