package com.app.recharge.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.order")
public record OrderProperties(
        @DefaultValue("15m") Duration ttl,
        @DefaultValue("3") int maxPaymentAttempts) { }
