package com.app.recharge.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** What was recommended and why, for A/B analysis. Written only for users who gave personalization consent. */
@Entity
@Table(name = "recommendation_log")
public class RecommendationLog {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "number_id")
    private UUID numberId;

    @Column(name = "plan_id")
    private UUID planId;

    private String reason;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "created_at")
    private Instant createdAt;

    protected RecommendationLog() {
        // for JPA
    }

    /** Records one recommendation: which plan, why, and which recommender version produced it. */
    public RecommendationLog(UUID userId, UUID numberId, UUID planId, String reason, String modelVersion, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.numberId = numberId;
        this.planId = planId;
        this.reason = reason;
        this.modelVersion = modelVersion;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public String getReason() { return reason; }
}
