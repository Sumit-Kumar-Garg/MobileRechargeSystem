package com.app.recharge.catalog;

import com.app.recharge.catalog.spi.PlanProvider;
import com.app.recharge.catalog.spi.ProviderPlan;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies each provider's plans into the catalog. One provider failing never affects another, and a broken
 * or empty feed never takes a provider's plans off sale:
 * - a plan that fails validation is skipped (and counted), the rest of the feed is still applied;
 * - plans missing from a non-empty feed are deactivated, never deleted (orders keep referencing them);
 * - an exception or an empty feed leaves the provider's plans untouched.
 */
@Service
public class CatalogSyncService {

    public record SyncResult(String provider, int upserted, int deactivated, int rejected, boolean failed) { }

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);
    private static final Pattern ID = Pattern.compile("^[a-z0-9-]{2,40}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._-]{1,40}$");
    private static final Pattern MARKET = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    static final int MAX_BENEFITS = 10;

    private final List<PlanProvider> providers;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CacheManager cacheManager;
    private final Clock clock;

    public CatalogSyncService(List<PlanProvider> providers, JdbcTemplate jdbc, TransactionTemplate tx,
                              CacheManager cacheManager, Clock clock) {
        this.providers = providers;
        this.jdbc = jdbc;
        this.tx = tx;
        this.cacheManager = cacheManager;
        this.clock = clock;
    }

    /** Syncs every registered plan provider one after another and returns one result per provider. */
    public List<SyncResult> syncAll() {
        List<SyncResult> results = new ArrayList<>();
        for (PlanProvider provider : providers) {
            results.add(sync(provider));
        }
        return results;
    }

    /**
     * Fetches one provider's feed, drops invalid plans, upserts the rest and deactivates plans missing from the feed in
     * one transaction, then clears the plans cache. A failed or empty feed leaves the provider's plans unchanged and is
     * reported with {@code failed = true}.
     */
    public SyncResult sync(PlanProvider provider) {
        String providerId = provider.id();
        if (providerId == null || !ID.matcher(providerId).matches()) {
            log.error("Plan provider with invalid id '{}' ignored", providerId);
            return new SyncResult(String.valueOf(providerId), 0, 0, 0, true);
        }
        List<ProviderPlan> feed;
        try {
            feed = provider.fetchPlans();
        } catch (RuntimeException e) {
            log.warn("Plan provider {} could not be reached; keeping its current plans", providerId, e);
            return new SyncResult(providerId, 0, 0, 0, true);
        }
        if (feed == null || feed.isEmpty()) {
            log.warn("Plan provider {} returned no plans; keeping its current plans", providerId);
            return new SyncResult(providerId, 0, 0, 0, true);
        }
        List<ProviderPlan> valid = feed.stream().filter(CatalogSyncService::isValid).toList();
        int rejected = feed.size() - valid.size();
        if (rejected > 0) {
            log.warn("Plan provider {}: {} invalid plan(s) skipped", providerId, rejected);
        }
        if (valid.isEmpty()) {
            return new SyncResult(providerId, 0, 0, rejected, true);
        }
        Integer deactivated = tx.execute(status -> apply(providerId, valid));
        Cache cache = cacheManager.getCache("plans");
        if (cache != null) {
            cache.clear();
        }
        return new SyncResult(providerId, valid.size(), deactivated == null ? 0 : deactivated, rejected, false);
    }

    /**
     * Upserts the given plans and their partner benefits, then deactivates this provider's active plans that are not in
     * the feed. Must run inside a transaction. Returns the number of plans deactivated.
     */
    private int apply(String providerId, List<ProviderPlan> plans) {
        Timestamp now = Timestamp.from(clock.instant());
        Set<String> keep = new HashSet<>();
        for (ProviderPlan p : plans) {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO plans (id, provider_id, code, market, name, price_minor, currency, validity_days,
                                       data_mb, voice_min, sms, active, synced_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                    ON CONFLICT (provider_id, market, code) DO UPDATE SET
                      name = EXCLUDED.name, price_minor = EXCLUDED.price_minor, currency = EXCLUDED.currency,
                      validity_days = EXCLUDED.validity_days, data_mb = EXCLUDED.data_mb,
                      voice_min = EXCLUDED.voice_min, sms = EXCLUDED.sms, active = TRUE, synced_at = EXCLUDED.synced_at
                    RETURNING id
                    """, UUID.class,
                    providerId, p.code(), p.market(), p.name(), p.priceMinor(), p.currency(), p.validityDays(),
                    p.dataMb(), p.voiceMin(), p.sms(), now);
            jdbc.update("DELETE FROM plan_benefits WHERE plan_id = ?", id);
            List<String> benefits = p.benefits() == null ? List.of() : p.benefits();
            for (int i = 0; i < Math.min(benefits.size(), MAX_BENEFITS); i++) {
                jdbc.update("INSERT INTO plan_benefits (id, plan_id, type, label, sort_order) VALUES (gen_random_uuid(), ?, 'PARTNER', ?, ?)",
                        id, truncate(benefits.get(i), 160), i + 1);
            }
            keep.add(p.market() + "|" + p.code());
        }
        int deactivated = 0;
        List<String[]> existing = jdbc.query(
                "SELECT market, code FROM plans WHERE provider_id = ? AND active = TRUE",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, providerId);
        for (String[] row : existing) {
            if (!keep.contains(row[0] + "|" + row[1])) {
                deactivated += jdbc.update(
                        "UPDATE plans SET active = FALSE, synced_at = ? WHERE provider_id = ? AND market = ? AND code = ?",
                        now, providerId, row[0], row[1]);
            }
        }
        return deactivated;
    }

    /**
     * Checks that a provider plan is safe to sell: well-formed code, market and currency, a non-blank name of at most
     * 120 characters, positive price, validity of 1 to 3650 days, and no negative allowances.
     */
    public static boolean isValid(ProviderPlan p) {
        return p != null
                && p.code() != null && CODE.matcher(p.code()).matches()
                && p.market() != null && MARKET.matcher(p.market()).matches()
                && p.currency() != null && CURRENCY.matcher(p.currency()).matches()
                && p.name() != null && !p.name().isBlank() && p.name().length() <= 120
                && p.priceMinor() > 0 && p.validityDays() > 0 && p.validityDays() <= 3650
                && nonNegative(p.dataMb()) && nonNegative(p.voiceMin()) && nonNegative(p.sms());
    }

    /** True when the allowance is unmetered (null) or zero or more. */
    private static boolean nonNegative(Integer value) {
        return value == null || value >= 0;
    }

    /** Cuts the value to at most {@code max} characters. */
    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
