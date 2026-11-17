package com.app.recharge.provisioning;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.provisioning")
public record ProvisioningProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2m") Duration staleAfter,
        @DefaultValue("5s") Duration baseBackoff) { }
