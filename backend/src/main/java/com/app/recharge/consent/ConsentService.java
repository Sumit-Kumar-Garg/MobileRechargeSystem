package com.app.recharge.consent;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Consent is opt-in: absence of a record means "not granted". */
@Service
public class ConsentService {

    private final ConsentRepository repository;
    private final Clock clock;

    public ConsentService(ConsentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** True only if the user explicitly granted this purpose. */
    public boolean isGranted(UUID userId, ConsentPurpose purpose) {
        return repository.findByUserIdAndPurpose(userId, purpose).map(Consent::isGranted).orElse(false);
    }

    /** Every purpose with the user's choice; purposes never set are reported as not granted. */
    public Map<ConsentPurpose, Boolean> all(UUID userId) {
        Map<ConsentPurpose, Boolean> result = new EnumMap<>(ConsentPurpose.class);
        for (ConsentPurpose p : ConsentPurpose.values()) {
            result.put(p, false);
        }
        repository.findByUserId(userId).forEach(c -> result.put(c.getPurpose(), c.isGranted()));
        return result;
    }

    /**
     * Creates or updates the user's consent for a purpose. Safe under concurrent calls: if another request created the
     * record first, that record is updated instead.
     */
    public void set(UUID userId, ConsentPurpose purpose, boolean granted) {
        var existing = repository.findByUserIdAndPurpose(userId, purpose);
        if (existing.isPresent()) {
            Consent c = existing.get();
            c.update(granted, clock.instant());
            repository.save(c);
            return;
        }
        try {
            repository.saveAndFlush(new Consent(userId, purpose, granted, clock.instant()));
        } catch (DataIntegrityViolationException race) {
            Consent c = repository.findByUserIdAndPurpose(userId, purpose).orElseThrow(() -> race);
            c.update(granted, clock.instant());
            repository.save(c);
        }
    }
}
