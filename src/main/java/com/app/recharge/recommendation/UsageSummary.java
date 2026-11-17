package com.app.recharge.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Monthly usage per number. In production this is fed by the operator's usage platform. */
@Entity
@Table(name = "usage_summary")
public class UsageSummary {

    @Id
    private UUID id;

    @Column(name = "number_id")
    private UUID numberId;

    private LocalDate month;

    @Column(name = "data_mb_used")
    private int dataMbUsed;

    @Column(name = "voice_min_used")
    private int voiceMinUsed;

    @Column(name = "sms_used")
    private int smsUsed;

    protected UsageSummary() {
        // for JPA
    }

    /** A usage row for one number and month, with the data used in MB. */
    public UsageSummary(UUID numberId, LocalDate month, int dataMbUsed) {
        this.id = UUID.randomUUID();
        this.numberId = numberId;
        this.month = month;
        this.dataMbUsed = dataMbUsed;
    }

    /** Replaces the month's data usage in MB. */
    public void setDataMbUsed(int dataMbUsed) {
        this.dataMbUsed = dataMbUsed;
    }

    public UUID getNumberId() { return numberId; }
    public LocalDate getMonth() { return month; }
    public int getDataMbUsed() { return dataMbUsed; }
    public int getVoiceMinUsed() { return voiceMinUsed; }
    public int getSmsUsed() { return smsUsed; }
}
