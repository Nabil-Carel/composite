package io.github.nabilcarel.composite.exception;

import lombok.Getter;

import java.time.Duration;

@Getter
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
}
