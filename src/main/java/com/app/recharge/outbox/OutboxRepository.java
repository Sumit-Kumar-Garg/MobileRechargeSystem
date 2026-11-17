package com.app.recharge.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /** Oldest first: ids are assigned in commit order closely enough, and one publisher at a time keeps the order. */
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /** Sets the published time of one event, in its own short transaction. */
    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.publishedAt = :now where e.id = :id")
    int markPublished(@Param("id") Long id, @Param("now") Instant now);

    /** Increments the failed-attempt counter of one event, in its own short transaction. */
    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.attempts = e.attempts + 1 where e.id = :id")
    int recordFailure(@Param("id") Long id);
}
