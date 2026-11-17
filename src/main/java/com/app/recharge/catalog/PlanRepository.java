package com.app.recharge.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Deliberately extends the bare Repository: the catalog is read-only, so no save/delete methods are exposed. */
public interface PlanRepository extends Repository<Plan, UUID> {

    /**
     * Loads the active plans of a market with their benefits in one query, ordered by price, then code, then provider.
     */
    @Query("""
            select distinct p from Plan p
            left join fetch p.benefits
            where p.market = :market and p.active = true
            order by p.priceMinor asc, p.code asc, p.providerId asc
            """)
    List<Plan> findActiveWithBenefits(@Param("market") String market);

    /** Finds a plan by id, only if it is still on sale. */
    Optional<Plan> findByIdAndActiveTrue(UUID id);
}
