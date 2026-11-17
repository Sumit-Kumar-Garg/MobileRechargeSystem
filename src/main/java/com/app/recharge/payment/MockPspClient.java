package com.app.recharge.payment;

import com.app.recharge.payment.spi.PspUnavailableException;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspRefundRequest;
import com.app.recharge.payment.spi.PspChargeRequest;
import com.app.recharge.payment.spi.PspClient;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock card provider ("mockpay"): cards in any currency. The payment token picks the behavior:
 * tok_success -> approved; tok_decline -> declined; tok_timeout -> the PSP approves but the response is lost
 * (we get a timeout; reconciliation later finds the approval); anything else -> declined as an invalid token.
 */
@Component
@ConditionalOnProperty(name = "app.psp.mode", havingValue = "mock")
public class MockPspClient implements PspClient {

    public static final String ID = "mockpay";

    private final Map<String, PspResult> results = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> methods() {
        return Set.of("card");
    }

    /** Card payments in any currency. */
    @Override
    public boolean supports(String currency, String method) {
        return "card".equals(method);
    }

    /**
     * Decides the outcome from the payment token and remembers it per merchant reference, so repeats return the same
     * result. For tok_timeout the approval is stored but the call throws, simulating a lost response.
     */
    @Override
    public PspResult charge(PspChargeRequest request) {
        PspResult existing = results.get(request.merchantRef());
        if (existing != null) {
            return existing;            // idempotent
        }
        String token = request.paymentToken();
        PspResult result = switch (token) {
            case "tok_success", "tok_timeout" -> PspResult.succeeded("psp_" + UUID.randomUUID());
            case "tok_decline" -> PspResult.declined("card_declined");
            default -> PspResult.declined("invalid_token");
        };
        results.put(request.merchantRef(), result);
        if ("tok_timeout".equals(token)) {
            throw new PspUnavailableException("simulated PSP timeout");
        }
        return result;
    }

    /** Always succeeds; repeated calls with the same reference return the same result. */
    @Override
    public PspResult refund(PspRefundRequest request) {
        return results.computeIfAbsent("refund:" + request.merchantRef(),
                k -> PspResult.succeeded("psp_refund_" + UUID.randomUUID()));
    }

    /** Voids the reference if nothing was recorded for it; otherwise returns the outcome already recorded. */
    @Override
    public PspResult cancel(String merchantRef) {
        return results.computeIfAbsent(merchantRef, k -> PspResult.declined("cancelled"));
    }

    /** The recorded outcome for a merchant reference, if any. */
    @Override
    public Optional<PspResult> lookup(String merchantRef) {
        return Optional.ofNullable(results.get(merchantRef));
    }
}
