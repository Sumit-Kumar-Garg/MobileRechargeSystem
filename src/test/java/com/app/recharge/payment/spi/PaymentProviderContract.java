package com.app.recharge.payment.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT TEST KIT for payment providers. A provider team extends this class, supplies its adapter (pointed
 * at the provider's sandbox or a stub) and two tokens, and must pass every test before the adapter is
 * switched on. The payments core relies on exactly these guarantees and nothing else.
 */
public abstract class PaymentProviderContract {

    protected abstract PspClient provider();

    /** A token the provider approves. */
    protected abstract String approvedToken();

    /** A token the provider declines. */
    protected abstract String declinedToken();

    /** A currency and method the provider claims to support. */
    protected String currency() {
        return "EUR";
    }

    protected String method() {
        return provider().methods().iterator().next();
    }

    private PspChargeRequest charge(String ref, String token) {
        return new PspChargeRequest(ref, 1500, currency(), token);
    }

    private static String ref() {
        return UUID.randomUUID().toString();
    }

    @Test
    void hasAStableLowerCaseId() {
        assertThat(provider().id()).matches("^[a-z0-9-]{2,40}$");
        assertThat(provider().id()).isEqualTo(provider().id());
    }

    @Test
    void declaresAtLeastOneMethodItActuallySupports() {
        assertThat(provider().methods()).isNotEmpty();
        assertThat(provider().supports(currency(), method())).isTrue();
        assertThat(provider().supports(currency(), "no-such-method")).isFalse();
    }

    @Test
    void approvesAndDeclines() {
        PspResult approved = provider().charge(charge(ref(), approvedToken()));
        assertThat(approved.status()).isEqualTo(PspResult.Status.SUCCEEDED);
        assertThat(approved.pspRef()).isNotBlank();

        PspResult declined = provider().charge(charge(ref(), declinedToken()));
        assertThat(declined.status()).isEqualTo(PspResult.Status.DECLINED);
        assertThat(declined.failureCode()).isNotBlank();
    }

    @Test
    void chargingTheSameReferenceTwiceChargesOnce() {
        String ref = ref();
        PspResult first = provider().charge(charge(ref, approvedToken()));
        PspResult second = provider().charge(charge(ref, approvedToken()));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void lookupFindsWhatWasChargedAndNothingElse() {
        String ref = ref();
        PspResult charged = provider().charge(charge(ref, approvedToken()));
        assertThat(provider().lookup(ref)).contains(charged);
        assertThat(provider().lookup(ref())).isEmpty();
    }

    @Test
    void aCancelledReferenceCanNeverBeChargedLater() {
        String ref = ref();
        assertThat(provider().cancel(ref).status()).isEqualTo(PspResult.Status.DECLINED);
        assertThat(provider().charge(charge(ref, approvedToken())).status()).isEqualTo(PspResult.Status.DECLINED);
    }

    @Test
    void cancellingAnApprovedChargeReportsThatItWentThrough() {
        String ref = ref();
        provider().charge(charge(ref, approvedToken()));
        assertThat(provider().cancel(ref).status()).isEqualTo(PspResult.Status.SUCCEEDED);
    }

    @Test
    void refundIsIdempotentPerReference() {
        String ref = ref();
        provider().charge(charge(ref, approvedToken()));
        PspRefundRequest refund = new PspRefundRequest(ref(), ref, 1500, currency());
        PspResult first = provider().refund(refund);
        assertThat(first.status()).isEqualTo(PspResult.Status.SUCCEEDED);
        assertThat(provider().refund(refund)).isEqualTo(first);
    }
}
