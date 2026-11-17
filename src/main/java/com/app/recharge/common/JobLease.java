package com.app.recharge.common;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A time-limited lease in the database, so that only one instance runs a given background job at a time.
 * If the holder dies, the lease simply expires and another instance takes over. Re-acquiring by the current
 * holder extends the lease.
 *
 * All times come from the database clock ({@code clock_timestamp()}), never from the instance: with several
 * instances, their clocks can drift apart, and a lease judged by two different clocks could be held twice.
 */
@Component
public class JobLease {

    private final JdbcTemplate jdbc;
    private final String owner = UUID.randomUUID().toString();

    public JobLease(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when this instance now holds (or still holds) the lease for {@code ttl} */
    public boolean tryAcquire(String name, Duration ttl) {
        int updated = jdbc.update("""
                INSERT INTO job_leases (name, owner, locked_until)
                VALUES (?, ?, clock_timestamp() + ? * INTERVAL '1 millisecond')
                ON CONFLICT (name) DO UPDATE
                  SET owner = EXCLUDED.owner, locked_until = EXCLUDED.locked_until
                  WHERE job_leases.locked_until <= clock_timestamp() OR job_leases.owner = EXCLUDED.owner
                """, name, owner, ttl.toMillis());
        return updated == 1;
    }

    /** Gives the lease up early so another instance does not have to wait for it to expire. */
    public void release(String name) {
        jdbc.update("UPDATE job_leases SET locked_until = clock_timestamp() WHERE name = ? AND owner = ?",
                name, owner);
    }
}
