package com.app.recharge.security;

import com.app.recharge.common.CorrelationIdFilter;
import com.app.recharge.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Security failures use the same problem+json contract as every other error. */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public ProblemSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Missing or invalid token: 401 UNAUTHENTICATED as problem+json. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Authentication required");
    }

    /** Authenticated but not allowed: 403 FORBIDDEN as problem+json. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied");
    }

    /** Writes a problem+json body with status, code and the request's correlation id. */
    private void write(HttpServletResponse res, HttpStatus status, ErrorCode code, String detail) throws IOException {
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code.name());
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null) {
            body.put("correlationId", cid);
        }
        mapper.writeValue(res.getOutputStream(), body);
    }
}
