package com.app.recharge.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Builds bearer tokens signed with the demo-profile secret. */
public final class TestJwt {

    public static final String SECRET = "dev-only-secret-change-me-0123456789abcdef";
    private static final NimbusJwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableSecret<>(SECRET.getBytes(UTF_8)));

    private TestJwt() { }

    public static String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("recharge-demo").subject(userId.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        String token = ENCODER.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return "Bearer " + token;
    }
}
