package com.app.recharge.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single injectable time source so time-dependent logic is testable. Always UTC. */
@Configuration
public class ClockConfig {
    /** The application-wide clock (system time, UTC). Tests replace it with a fixed clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
