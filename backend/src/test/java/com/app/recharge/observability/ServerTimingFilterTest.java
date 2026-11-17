package com.app.recharge.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServerTimingFilterTest {

    private final ServerTimingFilter filter = new ServerTimingFilter();

    @Test
    void addsServerTimingHeaderAndKeepsTheBodyIntact() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/plans");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            res.setContentType("application/json");
            res.getWriter().write("{\"ok\":true}");
        });

        assertThat(response.getHeader("Server-Timing")).matches("app;dur=\\d+\\.\\d");
        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
        assertThat(response.getContentType()).isEqualTo("application/json");
    }

    @Test
    void durationReflectsHandlerTime() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/plans");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        double ms = Double.parseDouble(response.getHeader("Server-Timing").replace("app;dur=", ""));
        assertThat(ms).isGreaterThanOrEqualTo(25.0);
    }

    @Test
    void headerIsStillAddedWhenTheHandlerFails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/plans");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            assertThat(expected).isInstanceOf(IllegalStateException.class);
        }

        assertThat(response.getHeader("Server-Timing")).startsWith("app;dur=");
    }
}
