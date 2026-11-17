package com.app.recharge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.recharge.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T10:00:00Z"));

    private RateLimitFilter filter(int globalPerMinute) {
        return new RateLimitFilter(new InMemoryRateLimiter(clock),
                new RateLimitProperties(true, "memory", globalPerMinute), new ObjectMapper());
    }

    private MockHttpServletResponse call(RateLimitFilter filter, String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void endpointPolicyBlocksTheSixthLinkAttemptOfTheDay() throws Exception {
        RateLimitFilter f = filter(1000);
        for (int i = 0; i < 5; i++) {
            assertThat(call(f, "POST", "/v1/numbers").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call(f, "POST", "/v1/numbers");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
        assertThat(blocked.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void policiesOnlyApplyToTheirOwnMethodAndPath() throws Exception {
        RateLimitFilter f = filter(1000);
        for (int i = 0; i < 10; i++) {
            assertThat(call(f, "GET", "/v1/numbers").getStatus()).isEqualTo(200);   // GET is not limited
        }
    }

    @Test
    void globalCeilingAppliesToEverything() throws Exception {
        RateLimitFilter f = filter(2);
        assertThat(call(f, "GET", "/v1/plans").getStatus()).isEqualTo(200);
        assertThat(call(f, "GET", "/v1/plans").getStatus()).isEqualTo(200);
        assertThat(call(f, "GET", "/v1/plans").getStatus()).isEqualTo(429);
    }

    @Test
    void healthChecksAreNeverLimited() throws Exception {
        RateLimitFilter f = filter(1);
        for (int i = 0; i < 5; i++) {
            assertThat(call(f, "GET", "/actuator/health").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void differentClientsHaveSeparateBudgets() throws Exception {
        RateLimitFilter f = filter(1);
        assertThat(call(f, "GET", "/v1/plans").getStatus()).isEqualTo(200);
        assertThat(call(f, "GET", "/v1/plans").getStatus()).isEqualTo(429);

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/v1/plans");
        other.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(other, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void oversizedBodiesAreRejectedEarly() throws Exception {
        RequestSizeFilter size = new RequestSizeFilter(new ObjectMapper(), RateLimitProperties.defaults());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/orders");
        request.setContent(new byte[(int) RateLimitProperties.defaults().requestSize().maxBody().toBytes() + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        size.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void telemetryEndpointAllowsLargerBodies() throws Exception {
        RequestSizeFilter size = new RequestSizeFilter(new ObjectMapper(), RateLimitProperties.defaults());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/telemetry/batch");
        request.setContent(new byte[(int) RateLimitProperties.defaults().requestSize().maxBody().toBytes() * 4]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        size.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void configuredPolicyOverrideReplacesTheDefaultLimit() throws Exception {
        RateLimitProperties props = new RateLimitProperties(true, "memory", 1000,
                java.util.Map.of("numbers-link", new RateLimitProperties.PolicyLimit(2, null)), null, null);
        RateLimitFilter f = new RateLimitFilter(new InMemoryRateLimiter(clock), props, new ObjectMapper());

        assertThat(call(f, "POST", "/v1/numbers").getStatus()).isEqualTo(200);
        assertThat(call(f, "POST", "/v1/numbers").getStatus()).isEqualTo(200);
        assertThat(call(f, "POST", "/v1/numbers").getStatus()).isEqualTo(429);       // 2, not the default 5
    }

    @Test
    void misspelledPolicyNameFailsInsteadOfBeingIgnored() {
        RateLimitProperties props = new RateLimitProperties(true, "memory", 1000,
                java.util.Map.of("number-link", new RateLimitProperties.PolicyLimit(2, null)), null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RateLimitFilter(new InMemoryRateLimiter(clock), props, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("number-link")
                .hasMessageContaining("numbers-link");
    }
}
