package com.app.recharge.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Read-only view of the plan catalog. Catalog changes happen through migrations / an admin tool, not this service. */
@Entity
@Immutable
@Table(name = "plans")
public class Plan {

    @Id
    private UUID id;

    @Column(name = "provider_id")
    private String providerId;

    private String code;
    private String market;
    private String name;

    @Column(name = "price_minor")
    private int priceMinor;

    private String currency;

    @Column(name = "validity_days")
    private int validityDays;

    @Column(name = "data_mb")
    private Integer dataMb;

    @Column(name = "voice_min")
    private Integer voiceMin;

    private Integer sms;
    private boolean active;

    @Column(name = "created_at")
    private Instant createdAt;

    @OneToMany(mappedBy = "plan", fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<PlanBenefit> benefits = new ArrayList<>();

    protected Plan() {
        // for JPA
    }

    public UUID getId() { return id; }
    public String getProviderId() { return providerId; }
    public String getCode() { return code; }
    public String getMarket() { return market; }
    public String getName() { return name; }
    public int getPriceMinor() { return priceMinor; }
    public String getCurrency() { return currency; }
    public int getValidityDays() { return validityDays; }
    public Integer getDataMb() { return dataMb; }
    public Integer getVoiceMin() { return voiceMin; }
    public Integer getSms() { return sms; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public List<PlanBenefit> getBenefits() { return benefits; }
}
