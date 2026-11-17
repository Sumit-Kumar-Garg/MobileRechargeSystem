package com.app.recharge.provisioning;

import com.app.recharge.provisioning.spi.OcsUnavailableException;
import com.app.recharge.provisioning.spi.OcsResult;
import com.app.recharge.provisioning.spi.OcsRequest;
import com.app.recharge.provisioning.spi.OcsClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock OCS. Behavior is chosen by the last digits of the number:
 * ...0000 -> always unavailable (exercises retries and the refund path);
 * ...0001 -> unavailable for the first two attempts, then succeeds (exercises retry success);
 * ...9999 -> permanently rejected; anything else -> success.
 */
@Component
@ConditionalOnProperty(name = "app.ocs.mode", havingValue = "mock")
public class MockOcsClient implements OcsClient {

    private final Map<String, OcsResult> done = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    /**
     * Simulates activation according to the number's last digits (see class doc). Remembers the final result per
     * reference id, so repeats return the same answer.
     */
    @Override
    public OcsResult provision(OcsRequest request) {
        OcsResult existing = done.get(request.referenceId());
        if (existing != null) {
            return existing;                 // idempotent
        }
        String msisdn = request.msisdn();
        int attempt = calls.computeIfAbsent(request.referenceId(), k -> new AtomicInteger()).incrementAndGet();

        if (msisdn.endsWith("0000")) {
            throw new OcsUnavailableException("simulated OCS outage");
        }
        if (msisdn.endsWith("0001") && attempt <= 2) {
            throw new OcsUnavailableException("simulated transient OCS failure");
        }
        OcsResult result = msisdn.endsWith("9999")
                ? OcsResult.rejected("number_not_eligible")
                : OcsResult.success("ocs_" + UUID.randomUUID());
        done.put(request.referenceId(), result);
        return result;
    }

    /** Marks the reference as cancelled so it can no longer be activated. */
    @Override
    public void cancel(String referenceId) {
        done.put(referenceId, OcsResult.rejected("cancelled"));
    }
}
