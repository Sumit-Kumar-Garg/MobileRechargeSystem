package com.app.recharge.number;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriberNumberRepository extends JpaRepository<SubscriberNumber, UUID> {

    /** A number by id, only if it belongs to the user. */
    Optional<SubscriberNumber> findByIdAndUserId(UUID id, UUID userId);

    /** A user's number by the keyed hash of its E.164 value (used to detect duplicates). */
    Optional<SubscriberNumber> findByUserIdAndMsisdnHash(UUID userId, String msisdnHash);

    /** All of a user's numbers, oldest first. */
    List<SubscriberNumber> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** How many numbers the user has linked. */
    long countByUserId(UUID userId);
}
