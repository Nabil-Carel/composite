package com.example.composite.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.time.Duration;

@ResponseStatus(HttpStatus.REQUEST_TIMEOUT)
public class RequestTimeoutException extends RuntimeException {
    private final Duration timeout;
    private final String referenceId;

    public RequestTimeoutException(String message) {
        super(message);
        this.timeout = null;
        this.referenceId = null;
    }

    public RequestTimeoutException(String message, Duration timeout, String referenceId) {
        super(message);
        this.timeout = timeout;
        this.referenceId = referenceId;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getReferenceId() {
        return referenceId;
    }
}
