package com.app.recharge.payment;

import com.app.recharge.payment.spi.PspUnavailableException;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspRefundRequest;
import com.app.recharge.payment.spi.PspChargeRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MockPspClientTest {

    private final MockPspClient psp = new MockPspClient();

    private static PspChargeRequest charge(String ref, String token) {
        return new PspChargeRequest(ref, 1500, "EUR", token);
    }

    @Test
    void successTokenApproves() {
        assertThat(psp.charge(charge("a", "tok_success")).status()).isEqualTo(PspResult.Status.SUCCEEDED);
    }

    @Test
    void declineAndUnknownTokensAreDeclined() {
        assertThat(psp.charge(charge("b", "tok_decline")).failureCode()).isEqualTo("card_declined");
        assertThat(psp.charge(charge("c", "whatever")).failureCode()).isEqualTo("invalid_token");
    }

    @Test
    void chargeIsIdempotentPerMerchantRef() {
        PspResult first = psp.charge(charge("d", "tok_success"));
        PspResult second = psp.charge(charge("d", "tok_decline"));   // same ref: first outcome wins
        assertThat(second).isEqualTo(first);
    }

    @Test
    void timeoutTokenThrowsButThePspHasApproved() {
        assertThatThrownBy(() -> psp.charge(charge("e", "tok_timeout"))).isInstanceOf(PspUnavailableException.class);

        assertThat(psp.lookup("e")).isPresent();
        assertThat(psp.lookup("e").get().status()).isEqualTo(PspResult.Status.SUCCEEDED);
        assertThat(psp.lookup("never-seen")).isEmpty();
    }

    @Test
    void cancellingAnUnknownReferenceVoidsItSoALateChargeCannotSucceed() {
        assertThat(psp.cancel("late").failureCode()).isEqualTo("cancelled");

        PspResult lateCharge = psp.charge(charge("late", "tok_success"));
        assertThat(lateCharge.status()).isEqualTo(PspResult.Status.DECLINED);
    }

    @Test
    void cancellingAnAlreadyApprovedChargeReportsThatItWentThrough() {
        psp.charge(charge("done", "tok_success"));
        assertThat(psp.cancel("done").status()).isEqualTo(PspResult.Status.SUCCEEDED);
    }

    @Test
    void refundIsIdempotent() {
        PspRefundRequest r = new PspRefundRequest("rf1", "e", 1500, "EUR");
        assertThat(psp.refund(r)).isEqualTo(psp.refund(r));
    }
}
