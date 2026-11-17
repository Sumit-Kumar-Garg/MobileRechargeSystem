package com.app.recharge.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String topic;

    @Column(name = "event_key")
    private String eventKey;

    private String type;
    private String payload;
    private int attempts;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // for JPA
    }

    /** A new, not yet published event for the given topic and key, with its JSON payload. */
    public OutboxEvent(String topic, String eventKey, String type, String payload, Instant now) {
        this.topic = topic;
        this.eventKey = eventKey;
        this.type = type;
        this.payload = payload;
        this.createdAt = now;
    }

    /** Marks the event as delivered to Kafka. */
    public void markPublished(Instant now) {
        this.publishedAt = now;
    }

    /** Counts one more failed publish attempt. */
    public void recordFailure() {
        this.attempts++;
    }

    public Long getId() { return id; }
    public String getTopic() { return topic; }
    public String getEventKey() { return eventKey; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
