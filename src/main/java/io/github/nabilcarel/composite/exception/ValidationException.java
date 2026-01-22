package io.github.nabilcarel.composite.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Collections;
import java.util.List;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ValidationException extends RuntimeException {
  private final List<List<String>> violations;

  public ValidationException(String message) {
    super(message);
    this.violations = Collections.emptyList();
  }

  public ValidationException(String message, List<List<String>> violations) {
    super(message);
    this.violations = violations;
  }

  public List<List<String>> getViolations() {
    return violations;
  }
}
