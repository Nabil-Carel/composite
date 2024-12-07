package com.example.composite.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class CompositeExecutionException extends RuntimeException {
    private final String referenceId;
    
    public CompositeExecutionException(String message) {
        super(message);
        this.referenceId = null;
    }

    public CompositeExecutionException(String message, String referenceId) {
        super(message);
        this.referenceId = referenceId;
    }

    public CompositeExecutionException(String message, String referenceId, Throwable cause) {
        super(message, cause);
        this.referenceId = referenceId;
    }

    public String getReferenceId() {
        return referenceId;
    }
}
