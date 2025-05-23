package com.example.composite.exception;

public class ResponseParsingException extends RuntimeException {
    public ResponseParsingException(String message, Throwable cause) {
        super(message, cause);
    }
} 