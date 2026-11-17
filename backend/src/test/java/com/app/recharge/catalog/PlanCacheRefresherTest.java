package com.app.recharge.catalog;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class PlanCacheRefresherTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CacheManager cacheManager = mock(CacheManager.class);
    private final Cache cache = mock(Cache.class);
    private final PlanCacheRefresher refresher = new PlanCacheRefresher(jdbc, cacheManager);

    PlanCacheRefresherTest() {
        when(cacheManager.getCache("plans")).thenReturn(cache);
    }

    private void versionIs(String version) {
        when(jdbc.queryForObject(eq(PlanCacheRefresher.VERSION_SQL), eq(String.class))).thenReturn(version);
    }

    @Test
    void firstCheckOnlyRecordsTheVersion() {
        versionIs("v1");
        refresher.refresh();
        verify(cache, never()).clear();
    }

    @Test
    void clearsTheLocalCacheWhenTheCatalogChanged() {
        versionIs("v1");
        refresher.refresh();
        refresher.refresh();
        verify(cache, never()).clear();          // unchanged: keep the cache

        versionIs("v2");
        refresher.refresh();
        verify(cache, times(1)).clear();
    }

    @Test
    void aDatabaseErrorIsSwallowed() {
        when(jdbc.queryForObject(eq(PlanCacheRefresher.VERSION_SQL), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        refresher.refresh();                     // must not throw: the TTL still bounds staleness
        verify(cache, never()).clear();
    }
}
