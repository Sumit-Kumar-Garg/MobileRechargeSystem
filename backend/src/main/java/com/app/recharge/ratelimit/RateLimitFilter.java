package com.app.recharge.ratelimit;

import com.app.recharge.common.CorrelationIdFilter;
import com.app.recharge.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user (or per-IP when anonymous) limits. Runs after Spring Security so the user is known.
 * A global ceiling applies to everything; sensitive endpoints get tighter limits of their own.
 * Which endpoints are covered is fixed here; each policy's limit and window come from
 * app.ratelimit.policies.&lt;name&gt; (defaults below), so they can be tuned per environment.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@ConditionalOnProperty(name = "app.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    record Policy(String name, String method, Pattern path, int limit, Duration window) { }

    /** The covered endpoints with their default limits. Config overrides limit/window by policy name. */
    static final List<Policy> DEFAULT_POLICIES = List.of(
            new Policy("orders-create", "POST", Pattern.compile("^/v1/orders$"), 10, Duration.ofHours(1)),
            new Policy("orders-pay", "POST", Pattern.compile("^/v1/orders/[^/]+/pay$"), 10, Duration.ofHours(1)),
            new Policy("numbers-link", "POST", Pattern.compile("^/v1/numbers$"), 5, Duration.ofDays(1)),
            new Policy("recommendations", "GET", Pattern.compile("^/v1/recommendations$"), 20, Duration.ofHours(1)),
            new Policy("telemetry", "POST", Pattern.compile("^/v1/telemetry/batch$"), 60, Duration.ofMinutes(1)),
            new Policy("devices-put", "PUT", Pattern.compile("^/v1/devices$"), 20, Duration.ofHours(1)),
            new Policy("devices-delete", "DELETE", Pattern.compile("^/v1/devices$"), 20, Duration.ofHours(1)));

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final ObjectMapper mapper;
    private final List<Policy> policies;

    /** Builds the effective policies: defaults with any configured overrides. Unknown policy names fail startup. */
    public RateLimitFilter(RateLimiter limiter, RateLimitProperties props, ObjectMapper mapper) {
        this.limiter = limiter;
        this.props = props;
        this.mapper = mapper;
        this.policies = effectivePolicies(props.policies());
    }

    /**
     * Applies configured overrides to the default policies. A misspelled policy name would otherwise be silently
     * ignored and leave an endpoint on its default, so it stops the application instead.
     */
    static List<Policy> effectivePolicies(Map<String, RateLimitProperties.PolicyLimit> overrides) {
        Set<String> known = DEFAULT_POLICIES.stream().map(Policy::name).collect(Collectors.toSet());
        for (String name : overrides.keySet()) {
            if (!known.contains(name)) {
                throw new IllegalStateException("Unknown rate limit policy app.ratelimit.policies." + name
                        + "; known policies: " + new TreeSet<>(known));
            }
        }
        return DEFAULT_POLICIES.stream().map(p -> {
            RateLimitProperties.PolicyLimit o = overrides.get(p.name());
            if (o == null) {
                return p;
            }
            return new Policy(p.name(), p.method(), p.path(),
                    o.limit() != null ? o.limit() : p.limit(),
                    o.window() != null ? o.window() : p.window());
        }).toList();
    }

    /** Health checks are never rate limited. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    /**
     * Applies the global per-minute limit, then any endpoint policy matching the method and path. Over the limit,
     * responds 429 with Retry-After; otherwise sets X-RateLimit-Remaining for matched policies.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String principal = principal(request);

        RateLimitResult global = limiter.hit("g:" + principal, props.globalPerMinute(), Duration.ofMinutes(1));
        if (!global.allowed()) {
            reject(response, global);
            return;
        }
        for (Policy p : policies) {
            if (p.method().equals(request.getMethod()) && p.path().matcher(request.getRequestURI()).matches()) {
                RateLimitResult r = limiter.hit("p:" + p.name() + ":" + principal, p.limit(), p.window());
                if (!r.allowed()) {
                    reject(response, r);
                    return;
                }
                response.setHeader("X-RateLimit-Remaining", String.valueOf(r.remaining()));
            }
        }
        chain.doFilter(request, response);
    }

    /** Who the limits apply to: the user id from the token, or the client IP for anonymous requests. */
    private static String principal(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return "u:" + jwt.getToken().getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** Writes a 429 RATE_LIMITED problem+json response with a Retry-After header. */
    private void reject(HttpServletResponse response, RateLimitResult result) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Too Many Requests");
        body.put("status", 429);
        body.put("detail", "Rate limit exceeded");
        body.put("code", ErrorCode.RATE_LIMITED.name());
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null) {
            body.put("correlationId", cid);
        }
        mapper.writeValue(response.getOutputStream(), body);
    }
}
