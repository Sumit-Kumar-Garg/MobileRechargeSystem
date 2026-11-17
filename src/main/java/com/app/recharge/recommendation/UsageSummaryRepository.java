package com.app.recharge.recommendation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageSummaryRepository extends JpaRepository<UsageSummary, UUID> {

    /** The number's 3 most recent months of usage. */
    List<UsageSummary> findTop3ByNumberIdOrderByMonthDesc(UUID numberId);

    /** The number's 6 most recent months of usage. */
    List<UsageSummary> findTop6ByNumberIdOrderByMonthDesc(UUID numberId);

    /** The number's usage for one month, if recorded. */
    Optional<UsageSummary> findByNumberIdAndMonth(UUID numberId, LocalDate month);
}
