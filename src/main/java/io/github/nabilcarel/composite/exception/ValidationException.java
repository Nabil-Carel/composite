package io.github.nabilcarel.composite.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when a composite request fails validation.
 *
 * <p>Annotated with {@code @ResponseStatus(BAD_REQUEST)} so that Spring MVC maps it to a
 * {@code 400} response when it is not caught by the composite execution pipeline.
 * The {@link #violations} field carries structured constraint violation details.
 *
 * @see io.github.nabilcarel.composite.service.CompositeRequestValidator
 * @since 0.0.1
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ValidationException extends RuntimeException {

    /**
     * Structured constraint violation details, where each inner list represents one
     * violation (e.g. field path + message).
     */
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
