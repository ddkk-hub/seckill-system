package com.ddk.seckill.controller;

import com.ddk.seckill.entity.ApiError;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@ConditionalOnProperty(name="seckill.engineering.enabled", havingValue="true")
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> expected(ResponseStatusException e) {
        String reason = e.getReason();
        String code = reason != null && reason.matches("[A-Z][A-Z0-9_]{0,79}") ? reason : "REQUEST_REJECTED";
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders())
            .body(error(e.getStatusCode().value(), code, message(e.getStatusCode().value())));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalid(Exception e) {
        return response(400, "INVALID_REQUEST", "Required parameter or header is missing or invalid");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> missing(NoResourceFoundException e) {
        return response(404, "RESOURCE_NOT_FOUND", "Requested resource was not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) headers.setAllow(e.getSupportedHttpMethods());
        return ResponseEntity.status(405).headers(headers).body(error(405, "METHOD_NOT_ALLOWED", "HTTP method is not supported"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> dependency(DataAccessException e) {
        log.error("Database or cache operation failed", e);
        return response(503, "DEPENDENCY_UNAVAILABLE", "Service temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse known) {
            return ResponseEntity.status(known.getStatusCode()).headers(known.getHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(known.getStatusCode().value(), "HTTP_REQUEST_REJECTED", "HTTP request is not supported"));
        }
        log.error("Unhandled request failure", e);
        return response(500, "INTERNAL_ERROR", "Unexpected server error; provide traceId when reporting");
    }

    private ResponseEntity<ApiError> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(error(status, code, message));
    }

    private ApiError error(int status, String code, String message) {
        return new ApiError(Instant.now(), status, code, message, MDC.get("traceId"));
    }

    private String message(int status) {
        return switch (status) {
            case 400 -> "Invalid request";
            case 404 -> "Requested resource was not found";
            case 409 -> "Request conflicts with current state";
            case 429 -> "Rate limit exceeded; retry after the indicated delay";
            case 503 -> "Service temporarily unavailable";
            default -> "Request could not be completed";
        };
    }
}
