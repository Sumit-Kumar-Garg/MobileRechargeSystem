package com.app.recharge.catalog;

import com.app.recharge.common.JobLease;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pulls every plan provider's feed on a schedule. One instance at a time (lease). */
@Component
@ConditionalOnProperty(name = "app.workers.enabled", havingValue = "true", matchIfMissing = true)
public class CatalogSyncJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncJob.class);

    private final CatalogSyncService service;
    private final JobLease lease;

    public CatalogSyncJob(CatalogSyncService service, JobLease lease) {
        this.service = service;
        this.lease = lease;
    }

    /**
     * Scheduled entry point: if this instance wins the "catalog-sync" lease, syncs every plan provider and logs one
     * summary line per provider.
     */
    @Scheduled(initialDelayString = "${app.catalog.sync-initial-delay:PT10S}", fixedDelayString = "${app.catalog.sync-delay:PT15M}")
    public void run() {
        if (!lease.tryAcquire("catalog-sync", Duration.ofMinutes(10))) {
            return;
        }
        service.syncAll().forEach(r -> log.info(
                "Catalog sync {}: {} upserted, {} deactivated, {} rejected{}",
                r.provider(), r.upserted(), r.deactivated(), r.rejected(), r.failed() ? " (feed not applied)" : ""));
    }
}
