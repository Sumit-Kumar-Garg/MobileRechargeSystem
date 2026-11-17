package com.app.recharge.catalog;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every instance's local plans cache in step with the database.
 *
 * Each instance caches the catalog in its own memory (Caffeine). The catalog sync runs on one instance only and
 * clears only that instance's cache, so the others would keep serving old plans until their entries expire.
 * Instead, every instance (deliberately no lease) reads a tiny catalog "version" every few seconds and clears its
 * own cache when the version changes. The query touches a few dozen rows, and a missed change is still bounded by
 * the cache TTL.
 */
@Component
@ConditionalOnProperty(name = "app.catalog.cache-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class PlanCacheRefresher {

    private static final Logger log = LoggerFactory.getLogger(PlanCacheRefresher.class);

    /** Changes whenever a plan is synced, priced, activated or deactivated. */
    static final String VERSION_SQL = """
            SELECT coalesce(max(synced_at)::text, '') || '|' || count(*) FILTER (WHERE active)
                   || '|' || coalesce(sum(price_minor) FILTER (WHERE active), 0)
            FROM plans
            """;

    private final JdbcTemplate jdbc;
    private final CacheManager cacheManager;
    private final AtomicReference<String> lastVersion = new AtomicReference<>();

    public PlanCacheRefresher(JdbcTemplate jdbc, CacheManager cacheManager) {
        this.jdbc = jdbc;
        this.cacheManager = cacheManager;
    }

    /**
     * Scheduled check (every 10 s by default): clears this instance's plans cache if the catalog version changed
     * since the last check. The first check only records the version. Errors are logged; the TTL still applies.
     */
    @Scheduled(fixedDelayString = "${app.catalog.cache-refresh.delay:PT10S}")
    public void refresh() {
        try {
            String current = jdbc.queryForObject(VERSION_SQL, String.class);
            String previous = lastVersion.getAndSet(current);
            if (previous != null && !Objects.equals(previous, current)) {
                Cache cache = cacheManager.getCache("plans");
                if (cache != null) {
                    cache.clear();
                }
                log.info("Catalog changed, local plans cache cleared");
            }
        } catch (RuntimeException e) {
            log.warn("Could not check the catalog version: {}", e.getMessage());
        }
    }
}
