package com.app.recharge.catalog.spi;

import java.util.List;

/**
 * CONTRACT between the catalog and a supplier of plans (one implementation per plan provider).
 * The catalog pulls the provider's full list of plans on a schedule and stores a validated copy, so browsing
 * and ordering never depend on the provider being reachable.
 *
 * Rules: return the COMPLETE current list (plans missing from it are taken off sale); throw any exception
 * when the list cannot be fetched (the previous copy then stays as it is); plan codes are stable.
 */
public interface PlanProvider {

    /** Stable provider id, lower case, for example "travelsim". The fulfilment system uses the same id. */
    String id();

    /**
     * Returns the provider's complete current list of plans. Throws if the list cannot be fetched, so the catalog keeps
     * its previous copy.
     */
    List<ProviderPlan> fetchPlans();
}
