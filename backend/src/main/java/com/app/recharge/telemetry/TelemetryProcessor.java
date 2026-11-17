package com.app.recharge.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores one event. Journey events are inserted idempotently (same id twice is a no-op); network calls
 * are folded into hourly rollups with cumulative latency buckets. Used by the Kafka consumer, the inline
 * sink. Server-side journey events arrive here through the outbox and the telemetry topic.
 */
@Component
public class TelemetryProcessor {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TelemetryProcessor(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Stores one event: network calls are added to the hourly rollups; everything else is stored as a journey event.
     */
    public void process(TelemetryEnvelope e) {
        if ("network_call".equals(e.type())) {
            RollupRow row = RollupRow.from(e);
            if (row != null) {
                upsertRollup(row);
            }
        } else {
            insertJourney(e);
        }
    }

    /** Inserts a journey event; an event id seen before is ignored, so redelivery is harmless. */
    private void insertJourney(TelemetryEnvelope e) {
        String props;
        try {
            props = mapper.writeValueAsString(e.props() == null ? java.util.Map.of() : e.props());
        } catch (JsonProcessingException ex) {
            props = "{}";
        }
        jdbc.update("""
                INSERT INTO journey_events
                  (id, user_pseudo_id, session_id, journey_id, order_id, source, type, name, occurred_at, props)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                e.id(), e.userPseudoId(), e.sessionId(), e.journeyId(), e.orderId(), e.source(),
                e.type(), e.name(), Timestamp.from(e.occurredAt()), props);
    }

    /**
     * Adds one network call to its hourly rollup row (endpoint, network type, app version): call and error counts,
     * latency sums and cumulative latency buckets.
     */
    private void upsertRollup(RollupRow r) {
        long d = r.durationMs();
        jdbc.update("""
                INSERT INTO telemetry_rollups
                  (bucket_hour, endpoint, network_type, app_version, calls, errors, sum_ms, sum_server_ms,
                   le_100, le_250, le_500, le_1000, le_2500, le_inf)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (bucket_hour, endpoint, network_type, app_version) DO UPDATE SET
                  calls = telemetry_rollups.calls + 1,
                  errors = telemetry_rollups.errors + EXCLUDED.errors,
                  sum_ms = telemetry_rollups.sum_ms + EXCLUDED.sum_ms,
                  sum_server_ms = telemetry_rollups.sum_server_ms + EXCLUDED.sum_server_ms,
                  le_100 = telemetry_rollups.le_100 + EXCLUDED.le_100,
                  le_250 = telemetry_rollups.le_250 + EXCLUDED.le_250,
                  le_500 = telemetry_rollups.le_500 + EXCLUDED.le_500,
                  le_1000 = telemetry_rollups.le_1000 + EXCLUDED.le_1000,
                  le_2500 = telemetry_rollups.le_2500 + EXCLUDED.le_2500,
                  le_inf = telemetry_rollups.le_inf + 1
                """,
                Timestamp.from(r.bucketHour()), r.endpoint(), r.networkType(), r.appVersion(),
                r.error() ? 1 : 0, d, r.serverMs(),
                d <= 100 ? 1 : 0, d <= 250 ? 1 : 0, d <= 500 ? 1 : 0, d <= 1000 ? 1 : 0, d <= 2500 ? 1 : 0);
    }
}
