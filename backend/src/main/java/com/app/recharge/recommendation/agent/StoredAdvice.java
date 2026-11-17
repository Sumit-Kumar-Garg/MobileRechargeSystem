package com.app.recharge.recommendation.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The agent's latest advice for one number. One row per number; a new run replaces it. */
@Entity
@Table(name = "recommendation_advice")
public class StoredAdvice {

    @Id
    @Column(name = "number_id")
    private UUID numberId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "plan_code")
    private String planCode;

    private String explanation;

    private String language;

    private String model;

    private int steps;

    @Column(name = "input_tokens")
    private int inputTokens;

    @Column(name = "output_tokens")
    private int outputTokens;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "valid_until")
    private Instant validUntil;

    protected StoredAdvice() {
        // for JPA
    }

    /**
     * Builds the stored advice for a number from an agent result, valid for {@code ttl}. The model name is cut to 60
     * characters.
     */
    public static StoredAdvice of(UUID userId, UUID numberId, Advice advice, String language, Instant now, Duration ttl) {
        StoredAdvice s = new StoredAdvice();
        s.numberId = numberId;
        s.userId = userId;
        s.planId = advice.plan().id();
        s.planCode = advice.plan().code();
        s.explanation = advice.explanation();
        s.language = language;
        s.model = advice.model().length() <= 60 ? advice.model() : advice.model().substring(0, 60);
        s.steps = advice.steps();
        s.inputTokens = advice.inputTokens();
        s.outputTokens = advice.outputTokens();
        s.createdAt = now;
        s.validUntil = now.plus(ttl);
        return s;
    }

    public UUID getNumberId() { return numberId; }
    public UUID getPlanId() { return planId; }
    public String getPlanCode() { return planCode; }
    public String getExplanation() { return explanation; }
    public String getLanguage() { return language; }
    public String getModel() { return model; }
    public Instant getValidUntil() { return validUntil; }
}
