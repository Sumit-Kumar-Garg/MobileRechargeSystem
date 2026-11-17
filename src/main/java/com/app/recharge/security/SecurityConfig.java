package com.app.recharge.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Stateless JWT security: health, metrics, the plan list, demo login and API docs are public; everything else needs
     * a valid bearer token. Auth errors use the problem+json handlers.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ProblemSecurityHandlers handlers, JwtDecoder decoder)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)     // stateless bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/plans").permitAll()
                        .requestMatchers("/v1/demo/**").permitAll()   // controller only exists in the demo profile
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers));
        return http.build();
    }

    /**
     * Asymmetric verification against the identity provider's JWKS when {@code app.security.jwk-set-uri} is
     * set (production). Otherwise a shared HS256 secret, which is only acceptable for the demo and tests:
     * anyone holding a shared secret can mint tokens for any user.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder;
        if (!props.jwkSetUri().isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        } else {
            byte[] secret = props.jwtSecret().getBytes(UTF_8);
            if (secret.length < 32) {
                throw new IllegalStateException(
                        "Set app.security.jwk-set-uri (JWKS_URI), or for the demo a jwt-secret of at least 32 bytes");
            }
            log.warn("JWTs are verified with a shared secret. Use app.security.jwk-set-uri outside demos.");
            SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
            decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        }
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(props.issuer());
        if (!props.audience().isBlank()) {
            OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                    "aud", aud -> aud != null && aud.contains(props.audience()));
            validator = new DelegatingOAuth2TokenValidator<>(validator, audience);
        }
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
