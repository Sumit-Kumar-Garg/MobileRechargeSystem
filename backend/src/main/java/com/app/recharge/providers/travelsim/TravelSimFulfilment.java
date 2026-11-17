package com.app.recharge.providers.travelsim;

import com.app.recharge.provisioning.spi.OcsClient;
import com.app.recharge.provisioning.spi.OcsRequest;
import com.app.recharge.provisioning.spi.OcsResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Activates TravelSim plans. Same provider id as {@link TravelSimPlanProvider}, so its orders are routed here. */
@Component
@ConditionalOnProperty(name = "app.providers.travelsim.enabled", havingValue = "true")
public class TravelSimFulfilment implements OcsClient {

    private final Map<String, OcsResult> done = new ConcurrentHashMap<>();

    @Override
    public String providerId() {
        return TravelSimPlanProvider.ID;
    }

    /** Activates the plan; always succeeds and is idempotent per reference id. */
    @Override
    public OcsResult provision(OcsRequest request) {
        return done.computeIfAbsent(request.referenceId(), ref -> OcsResult.success("travelsim_" + UUID.randomUUID()));
    }

    /** Marks the reference as cancelled so it can no longer be activated. */
    @Override
    public void cancel(String referenceId) {
        done.put(referenceId, OcsResult.rejected("cancelled"));
    }
}
