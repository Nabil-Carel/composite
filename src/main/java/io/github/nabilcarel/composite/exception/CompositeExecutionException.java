package io.github.nabilcarel.composite.exception;

import lombok.Getter;

@Getter
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
}
