package com.app.recharge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Production: set {@code jwk-set-uri} (and {@code audience}) so tokens are verified with the identity
 * provider's public keys. The shared-secret mode exists for the demo profile and tests only.
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue("") String jwtSecret,
        @DefaultValue("recharge-demo") String issuer,
        @DefaultValue("") String jwkSetUri,
        @DefaultValue("") String audience) { }
