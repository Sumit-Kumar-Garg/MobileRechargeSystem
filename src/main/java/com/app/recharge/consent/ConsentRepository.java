package com.app.recharge.consent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRepository extends JpaRepository<Consent, UUID> {

    /** The user's consent record for one purpose, if they ever set it. */
    Optional<Consent> findByUserIdAndPurpose(UUID userId, ConsentPurpose purpose);

    /** All consent records the user has set. */
    List<Consent> findByUserId(UUID userId);
}
