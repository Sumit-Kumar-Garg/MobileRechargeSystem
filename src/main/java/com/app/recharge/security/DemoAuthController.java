package com.app.recharge.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mints a token for a demo user. Exists ONLY in the demo profile; real deployments use the company IdP. */
@RestController
@Profile("demo")
@RequestMapping("/v1/demo")
public class DemoAuthController {

    private static final Duration TTL = Duration.ofHours(1);

    private final JwtEncoder encoder;
    private final SecurityProperties props;
    private final Clock clock;

    public DemoAuthController(SecurityProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(props.jwtSecret().getBytes(UTF_8)));
    }

    /** POST /v1/demo/token: issues an HS256 token valid for 1 hour, for the given userId or a new random one. */
    @PostMapping("/token")
    public TokenResponse token(@RequestBody(required = false) TokenRequest request) {
        UUID userId = (request == null || request.userId() == null) ? UUID.randomUUID() : request.userId();
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .build();
        String token = encoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", TTL.toSeconds(), userId);
    }

    public record TokenRequest(UUID userId) { }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, UUID userId) { }
}
