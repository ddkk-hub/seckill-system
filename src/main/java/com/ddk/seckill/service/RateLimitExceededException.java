package com.ddk.seckill.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class RateLimitExceededException extends ResponseStatusException {
    private final long retrySeconds;

    public RateLimitExceededException(long retryMillis) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        retrySeconds = Math.max(1, (retryMillis + 999) / 1000);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retrySeconds));
        return headers;
    }
}
