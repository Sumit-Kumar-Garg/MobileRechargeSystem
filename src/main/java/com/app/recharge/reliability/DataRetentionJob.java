package com.app.recharge.reliability;

import com.app.recharge.common.JobLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** GDPR storage limitation: analytics are kept 13 months; delivered outbox rows are kept 7 days. */
@Component
@ConditionalOnProperty(name = "app.workers.enabled", havingValue = "true", matchIfMissing = true)
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final JobLease lease;
    private final Clock clock;

    public DataRetentionJob(JdbcTemplate jdbc, JobLease lease, Clock clock) {
        this.jdbc = jdbc;
        this.lease = lease;
        this.clock = clock;
    }

    /**
     * Nightly (03:30 by default), on one instance: deletes journey events and telemetry rollups older than 13 months
     * and published outbox rows older than 7 days.
     */
    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void run() {
        if (!lease.tryAcquire("data-retention", Duration.ofHours(1))) {
            return;
        }
        Instant now = clock.instant();
        Timestamp analyticsCutoff = Timestamp.from(now.atZone(ZoneOffset.UTC).minusMonths(13).toInstant());
        Timestamp outboxCutoff = Timestamp.from(now.minusSeconds(7 * 24 * 3600L));
        int events = jdbc.update("DELETE FROM journey_events WHERE occurred_at < ?", analyticsCutoff);
        int rollups = jdbc.update("DELETE FROM telemetry_rollups WHERE bucket_hour < ?", analyticsCutoff);
        int outbox = jdbc.update("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < ?", outboxCutoff);
        log.info("Retention: removed {} journey events, {} rollups, {} outbox rows", events, rollups, outbox);
    }
}
