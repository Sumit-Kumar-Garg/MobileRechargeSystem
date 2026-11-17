package com.app.recharge.telemetry;

import com.app.recharge.common.Topics;
import com.app.recharge.crypto.PiiCrypto;
import com.app.recharge.outbox.OutboxWriter;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Server-authoritative journey events (anything involving money or order state).
 * They are written to the outbox in the order's transaction and delivered to the telemetry topic, where the
 * same consumer that handles client events stores them. The money path therefore depends only on the outbox
 * table, never on the analytics tables: a problem in analytics storage cannot fail or slow a payment.
 */
@Component
public class JourneyRecorder {

    private final OutboxWriter outbox;
    private final PiiCrypto crypto;
    private final Clock clock;

    public JourneyRecorder(OutboxWriter outbox, PiiCrypto crypto, Clock clock) {
        this.outbox = outbox;
        this.crypto = crypto;
        this.clock = clock;
    }

    /**
     * Writes a server-side journey event (e.g. payment_succeeded) for the user to the telemetry outbox, in the caller's
     * transaction, keyed by the user's pseudonymous id.
     */
    public void record(String name, UUID userId, UUID journeyId, UUID orderId, Map<String, Object> props) {
        String pseudoId = pseudoId(userId);
        TelemetryEnvelope envelope = new TelemetryEnvelope(UUID.randomUUID(), "journey", name, clock.instant(),
                pseudoId, null, journeyId, orderId, "server", props, null);
        outbox.write(Topics.CLIENT_TELEMETRY, pseudoId, "journey." + name, envelope);
    }

    /** The user's pseudonymous analytics id: a keyed hash of the user id. */
    String pseudoId(UUID userId) {
        return crypto.hash("user:" + userId);
    }
}
