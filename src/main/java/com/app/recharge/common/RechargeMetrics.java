package com.app.recharge.common;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business metrics. Tag values are always small closed sets (never ids, numbers, or free text). */
@Component
public class RechargeMetrics {

    private final MeterRegistry registry;

    public RechargeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Counts an order reaching the given status. */
    public void orderTransition(String status) {
        registry.counter("recharge.orders", "status", status).increment();
    }

    /** Counts a payment attempt by outcome. */
    public void payment(String outcome) {
        registry.counter("recharge.payments", "outcome", outcome).increment();
    }

    /** Counts a provisioning attempt by outcome. */
    public void provisioning(String outcome) {
        registry.counter("recharge.provisioning", "outcome", outcome).increment();
    }

    /** Counts a notification by channel (push, SMS, email) and delivery status. */
    public void notification(String channel, String status) {
        registry.counter("recharge.notifications", "channel", channel, "status", status).increment();
    }

    /** Counts a client telemetry event that was dropped, by reason. */
    public void telemetryDropped(String reason) {
        registry.counter("recharge.telemetry.dropped", "reason", reason).increment();
    }

    /** Counts a telemetry value that was redacted because it looked like personal data. */
    public void piiRedaction() {
        registry.counter("recharge.telemetry.pii_redactions").increment();
    }

    /** Counts a recommendation served, by the reason it was chosen. */
    public void recommendation(String reason) {
        registry.counter("recharge.recommendations", "reason", reason).increment();
    }

    /** Outcome of one advisor agent run: advised, no_valid_answer, model_unavailable, rate_limited, error. */
    public void advisor(String outcome) {
        registry.counter("recharge.advisor.runs", "outcome", outcome).increment();
    }

    /** Model tokens used by advisor runs: the cost driver. */
    public void advisorTokens(int input, int output) {
        registry.counter("recharge.advisor.tokens", "direction", "input").increment(input);
        registry.counter("recharge.advisor.tokens", "direction", "output").increment(output);
    }

    /** Counts a Kafka message sent to the dead-letter topic of the given topic. */
    public void dlt(String topic) {
        registry.counter("recharge.kafka.dlt", "topic", topic).increment();
    }
}
