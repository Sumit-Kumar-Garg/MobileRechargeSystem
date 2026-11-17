package com.app.recharge.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event in the SAME transaction as the state change (MANDATORY propagation), so an event exists
 * if and only if the business change committed.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Serializes the payload to JSON and stores it as an outbox event. Must run inside the caller's transaction, so the
     * event is saved only if the business change commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String key, String type, Object payload) {
        try {
            repository.save(new OutboxEvent(topic, key, type, mapper.writeValueAsString(payload), clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize outbox payload for " + type, e);
        }
    }
}
