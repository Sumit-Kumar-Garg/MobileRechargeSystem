package com.app.recharge.number;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits for linked phone numbers (app.numbers.*).
 *
 * @param maxPerUser how many numbers one user may link
 */
@ConfigurationProperties("app.numbers")
public record NumberProperties(@DefaultValue("3") int maxPerUser) {

    public NumberProperties {
        if (maxPerUser < 1) {
            throw new IllegalArgumentException("app.numbers.max-per-user must be at least 1");
        }
    }

    /** The original defaults, for tests. */
    public static NumberProperties defaults() {
        return new NumberProperties(3);
    }
}
