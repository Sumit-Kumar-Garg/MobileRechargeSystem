package com.app.recharge.ratelimit;

import com.app.recharge.common.CorrelationIdFilter;
import com.app.recharge.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized bodies early using Content-Length. For chunked uploads (no length) enforce the limit
 * at the gateway / ingress as well; the gateway is the real line of defense.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeFilter extends OncePerRequestFilter {

    private final ObjectMapper mapper;
    private final long maxBody;
    private final long maxTelemetryBody;

    /** Limits from app.ratelimit.request-size (defaults: 16 KB, 256 KB for telemetry batches). */
    public RequestSizeFilter(ObjectMapper mapper, RateLimitProperties props) {
        this.mapper = mapper;
        this.maxBody = props.requestSize().maxBody().toBytes();
        this.maxTelemetryBody = props.requestSize().maxTelemetryBody().toBytes();
    }

    /**
     * Rejects requests whose Content-Length exceeds the configured limit (the telemetry batch has its own, larger
     * one) with 413 PAYLOAD_TOO_LARGE.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long max = "/v1/telemetry/batch".equals(request.getRequestURI()) ? maxTelemetryBody : maxBody;
        if (request.getContentLengthLong() > max) {
            response.setStatus(413);
            response.setContentType("application/problem+json");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "about:blank");
            body.put("title", "Payload Too Large");
            body.put("status", 413);
            body.put("detail", "Request body too large");
            body.put("code", ErrorCode.PAYLOAD_TOO_LARGE.name());
            String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (cid != null) {
                body.put("correlationId", cid);
            }
            mapper.writeValue(response.getOutputStream(), body);
            return;
        }
        chain.doFilter(request, response);
    }
}
