package io.github.nabilcarel.composite.exception;

import lombok.Getter;

/**
 * Thrown when an unrecoverable error occurs during the execution of a composite request.
 *
 * <p>When a {@link #referenceId} is available, it identifies the specific sub-request
 * during which the failure occurred, aiding diagnosis.
 *
 * @since 0.0.1
 */
@Getter
public class CompositeExecutionException extends RuntimeException {

    /**
     * The {@code referenceId} of the sub-request that triggered this exception, or
     * {@code null} when the failure is not attributable to a single sub-request.
     */
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
}
