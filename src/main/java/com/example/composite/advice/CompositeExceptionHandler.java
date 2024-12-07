package com.example.composite.advice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.composite.exception.CircularDependencyException;
import com.example.composite.exception.CompositeExecutionException;
import com.example.composite.exception.ReferenceResolutionException;
import com.example.composite.exception.RequestTimeoutException;
import com.example.composite.exception.ValidationException;

import lombok.extern.slf4j.Slf4j;

@ControllerAdvice
@Slf4j
public class CompositeExceptionHandler extends ResponseEntityExceptionHandler {
    
    private static final String TIMESTAMP = "timestamp";
    private static final String STATUS = "status";
    private static final String ERROR = "error";
    private static final String MESSAGE = "message";
    private static final String REFERENCE = "reference";
    private static final String REFERENCE_ID = "referenceId";
    private static final String VIOLATIONS = "violations";
    private static final String AVAILABLE_REFERENCES = "availableReferences";
    
    @ExceptionHandler(CircularDependencyException.class)
    public ResponseEntity<Object> handleCircularDependency(
            CircularDependencyException ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, Instant.now());
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(ERROR, "Circular Dependency Detected");
        body.put(MESSAGE, ex.getMessage());
        
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }
    
    @ExceptionHandler(CompositeExecutionException.class)
    public ResponseEntity<Object> handleCompositeExecution(
            CompositeExecutionException ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, Instant.now());
        body.put(STATUS, HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put(ERROR, "Execution Failed");
        body.put(MESSAGE, ex.getMessage());
        if (ex.getReferenceId() != null) {
            body.put(REFERENCE_ID, ex.getReferenceId());
        }
        
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
    
    @ExceptionHandler(ReferenceResolutionException.class)
    public ResponseEntity<Object> handleReferenceResolution(
            ReferenceResolutionException ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, Instant.now());
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(ERROR, "Reference Resolution Failed");
        body.put(MESSAGE, ex.getMessage());
        body.put(REFERENCE, ex.getReference());
        if (ex.getAvailableReferences() != null) {
            body.put(AVAILABLE_REFERENCES, ex.getAvailableReferences());
        }
        
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }
    
    @ExceptionHandler(RequestTimeoutException.class)
    public ResponseEntity<Object> handleRequestTimeout(
            RequestTimeoutException ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, Instant.now());
        body.put(STATUS, HttpStatus.REQUEST_TIMEOUT.value());
        body.put(ERROR, "Request Timeout");
        body.put(MESSAGE, ex.getMessage());
        if (ex.getTimeout() != null) {
            body.put("timeout", ex.getTimeout().toMillis() + "ms");
        }
        if (ex.getReferenceId() != null) {
            body.put(REFERENCE_ID, ex.getReferenceId());
        }
        
        return new ResponseEntity<>(body, HttpStatus.REQUEST_TIMEOUT);
    }
    
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Object> handleValidation(
            ValidationException ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, Instant.now());
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(ERROR, "Validation Failed");
        body.put(MESSAGE, ex.getMessage());
        body.put(VIOLATIONS, ex.getViolations());
        
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllUncaughtException(
            Exception ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TIMESTAMP, Instant.now());
        body.put(STATUS, HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put(ERROR, "Internal Server Error");
        body.put(MESSAGE, "An unexpected error occurred");
        
        log.error("Uncaught exception", ex);
        
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
