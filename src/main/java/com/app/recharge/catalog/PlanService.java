package com.app.recharge.catalog;

import com.app.recharge.common.BusinessException;
import com.app.recharge.common.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {

    private final PlanRepository plans;

    public PlanService(PlanRepository plans) {
        this.plans = plans;
    }

    /**
     * Cached per market in this instance's memory. After a catalog change every instance clears its cache within
     * about 10 s ({@link PlanCacheRefresher}); the 5 min TTL in application.yml is the upper bound if that check
     * fails. Returns DTOs, not entities, so nothing lazy escapes the transaction.
     */
    @Cacheable(cacheNames = "plans", key = "#market")
    @Transactional(readOnly = true)
    public List<PlanView> getActivePlans(String market) {
        return plans.findActiveWithBenefits(market).stream().map(PlanMapper::toView).toList();
    }

    /** Not cached: orders must always snapshot the live price. */
    @Transactional(readOnly = true)
    public PlanView getActivePlan(UUID id) {
        return plans.findByIdAndActiveTrue(id)
                .map(PlanMapper::toView)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_UNAVAILABLE, "Plan is not available"));
    }
}
