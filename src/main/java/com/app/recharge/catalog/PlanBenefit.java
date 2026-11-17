package com.app.recharge.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "plan_benefits")
public class PlanBenefit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    private String type;
    private String label;

    @Column(name = "sort_order")
    private int sortOrder;

    protected PlanBenefit() {
        // for JPA
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getLabel() { return label; }
    public int getSortOrder() { return sortOrder; }
}
