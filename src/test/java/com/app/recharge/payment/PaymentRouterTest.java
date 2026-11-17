package com.app.recharge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import com.app.recharge.payment.spi.PspClient;
import com.app.recharge.providers.altpay.AltPayClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentRouterTest {

    private final PspGateway gateway = mock(PspGateway.class);
    private final PspClient mockpay = new MockPspClient();      // card, any currency
    private final PspClient altpay = new AltPayClient();        // wallet anywhere; card in EUR and PLN

    @BeforeEach
    void setUp() {
        when(gateway.clients()).thenReturn(List.of(altpay, mockpay));       // registration order is not priority
        when(gateway.isAvailable("mockpay")).thenReturn(true);
        when(gateway.isAvailable("altpay")).thenReturn(true);
    }

    private PaymentRouter router(String... priority) {
        return new PaymentRouter(gateway, List.of(priority));
    }

    @Test
    void usesTheConfiguredPriorityAmongProvidersThatSupportThePayment() {
        assertThat(router("mockpay", "altpay").route("EUR", "card")).isEqualTo("mockpay");
        assertThat(router("altpay", "mockpay").route("EUR", "card")).isEqualTo("altpay");
    }

    @Test
    void onlyProvidersThatSupportTheMethodAndCurrencyAreConsidered() {
        assertThat(router("mockpay", "altpay").route("EUR", "wallet")).isEqualTo("altpay");
        assertThat(router("altpay", "mockpay").route("CZK", "card")).isEqualTo("mockpay");
    }

    @Test
    void aProviderWithAnOpenCircuitIsSkippedForNewPayments() {
        when(gateway.isAvailable("mockpay")).thenReturn(false);
        assertThat(router("mockpay", "altpay").route("EUR", "card")).isEqualTo("altpay");
    }

    @Test
    void whenEveryCandidateIsDownThePreferredOneIsStillChosen() {
        when(gateway.isAvailable("mockpay")).thenReturn(false);
        when(gateway.isAvailable("altpay")).thenReturn(false);
        assertThat(router("mockpay", "altpay").route("EUR", "card")).isEqualTo("mockpay");
    }

    @Test
    void anUnsupportedMethodIsAClientError() {
        assertThatThrownBy(() -> router("mockpay", "altpay").route("EUR", "crypto"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_METHOD_UNAVAILABLE));
    }

    @Test
    void providersMissingFromThePriorityListComeLast() {
        assertThat(router("altpay").route("EUR", "card")).isEqualTo("altpay");
        assertThat(router().route("CZK", "card")).isEqualTo("mockpay");
    }

    @Test
    void listsTheMethodsAvailableForACurrencyWithoutNamingProviders() {
        assertThat(router().methods("EUR")).containsExactly("card", "wallet");
    }
}
