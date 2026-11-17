package com.app.recharge.common;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error contract for the whole API: RFC 7807 problem+json with a stable "code" and the "correlationId".
 * Unexpected errors are logged with full detail but returned without internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Turns an expected business failure into problem+json with the status and code of its ErrorCode. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
        HttpStatus status = ex.getCode().getStatus();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setProperty("code", ex.getCode().name());
        return handleExceptionInternal(ex, pd, new HttpHeaders(), status, request);
    }

    /** Turns a failed bean-validation check (outside the MVC binding) into a 400 REQUEST_INVALID. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setProperty("code", ErrorCode.REQUEST_INVALID.name());
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    /** A concurrent update of the same row: 409 ORDER_STATE_INVALID, asking the client to retry. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource is being processed concurrently, please retry");
        pd.setProperty("code", ErrorCode.ORDER_STATE_INVALID.name());
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /** Any other exception: logs it in full and returns a generic 500 INTERNAL_ERROR without internals. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setProperty("code", ErrorCode.INTERNAL_ERROR.name());
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** Field names and messages only, never the rejected values (they may contain PII). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setProperty("code", ErrorCode.REQUEST_INVALID.name());
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    /**
     * Since Spring 6.1, a handler with constraint annotations on its parameters (e.g. @Pattern on a header) is
     * validated as a whole and raises this instead of MethodArgumentNotValidException. Same contract as above.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
        pd.setProperty("code", status.is4xxClientError() ? ErrorCode.REQUEST_INVALID.name() : ErrorCode.INTERNAL_ERROR.name());
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(f -> errors.add(
                        Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors().forEach(e -> errors.add(
                        Map.of("field", name, "message", String.valueOf(e.getDefaultMessage()))));
            }
        }
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    /** The name the client sent (header / query parameter name), falling back to the Java parameter name. */
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && !param.name().isEmpty()) {
            return param.name();
        }
        return String.valueOf(parameter.getParameterName());
    }

    /** Every framework-generated error (bad params, 404, 405, ...) also gets "code" and "correlationId". */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // Spring's own handlers pass a null body and let super build the ProblemDetail from the exception;
        // build it here instead so it can be enriched below.
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail pd) {
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                ErrorCode code;
                if (statusCode.value() == 404) {
                    code = ErrorCode.RESOURCE_NOT_FOUND;
                } else if (statusCode.is4xxClientError()) {
                    code = ErrorCode.REQUEST_INVALID;
                } else {
                    code = ErrorCode.INTERNAL_ERROR;
                }
                pd.setProperty("code", code.name());
            }
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                pd.setProperty("correlationId", correlationId);
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
