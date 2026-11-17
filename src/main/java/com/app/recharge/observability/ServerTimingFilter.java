package com.app.recharge.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Adds "Server-Timing: app;dur=..." so the mobile app can split total latency into server time and
 * network time. The response is buffered (small JSON only) so the header can carry the final duration.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ServerTimingFilter extends OncePerRequestFilter {

    /** Times the request, buffering the response so the Server-Timing header can be set before the body is sent. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            if (!wrapper.isCommitted()) {
                wrapper.setHeader("Server-Timing", String.format(Locale.ROOT, "app;dur=%.1f", ms));
            }
            wrapper.copyBodyToResponse();
        }
    }
}
