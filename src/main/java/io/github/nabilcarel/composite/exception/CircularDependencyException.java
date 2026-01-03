package io.github.nabilcarel.composite.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CircularDependencyException extends RuntimeException {
    public CircularDependencyException(String message) {
        super(message);
    }

    public CircularDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
