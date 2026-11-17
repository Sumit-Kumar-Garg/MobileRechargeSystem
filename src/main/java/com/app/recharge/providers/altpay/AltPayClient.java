package com.app.recharge.providers.altpay;

import com.app.recharge.payment.spi.PspChargeRequest;
import com.app.recharge.payment.spi.PspClient;
import com.app.recharge.payment.spi.PspRefundRequest;
import com.app.recharge.payment.spi.PspResult;
import com.app.recharge.payment.spi.PspUnavailableException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Example of a second payment provider owned by its own team. It lives in its own package, depends only on
 * the payment {@code spi} package, and is switched on by configuration. "altpay" offers wallet payments in
 * every currency and card payments in EUR and PLN.
 * Demo tokens: tok_success / tok_decline / tok_down (provider unreachable, nothing recorded).
 */
@Component
@ConditionalOnProperty(name = "app.providers.altpay.enabled", havingValue = "true")
public class AltPayClient implements PspClient {

    public static final String ID = "altpay";

    private final Map<String, PspResult> results = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> methods() {
        return Set.of("card", "wallet");
    }

    /** Wallet in any currency; card only in EUR and PLN. */
    @Override
    public boolean supports(String currency, String method) {
        return "wallet".equals(method) || ("card".equals(method) && Set.of("EUR", "PLN").contains(currency));
    }

    /**
     * Demo behaviour: tok_down throws as unreachable (nothing recorded), tok_success approves, anything else is
     * declined. Idempotent per merchant reference.
     */
    @Override
    public PspResult charge(PspChargeRequest request) {
        if ("tok_down".equals(request.paymentToken())) {
            throw new PspUnavailableException("altpay is unreachable");
        }
        return results.computeIfAbsent(request.merchantRef(), ref ->
                "tok_success".equals(request.paymentToken())
                        ? PspResult.succeeded("altpay_" + UUID.randomUUID())
                        : PspResult.declined("declined_by_altpay"));
    }

    /** Always succeeds; idempotent per refund reference. */
    @Override
    public PspResult refund(PspRefundRequest request) {
        return results.computeIfAbsent("refund:" + request.merchantRef(),
                ref -> PspResult.succeeded("altpay_refund_" + UUID.randomUUID()));
    }

    /** The recorded outcome for a merchant reference, if any. */
    @Override
    public Optional<PspResult> lookup(String merchantRef) {
        return Optional.ofNullable(results.get(merchantRef));
    }

    /** Voids the reference if nothing was recorded for it; otherwise returns the outcome already recorded. */
    @Override
    public PspResult cancel(String merchantRef) {
        return results.computeIfAbsent(merchantRef, ref -> PspResult.declined("cancelled"));
    }
}
