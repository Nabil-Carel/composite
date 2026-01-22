package io.github.nabilcarel.composite.exception;

import lombok.Getter;

@Getter
public class ReferenceResolutionException extends RuntimeException {
  private final String reference;
  private final String availableReferences;

  public ReferenceResolutionException(String message, String reference) {
    super(message);
    this.reference = reference;
    this.availableReferences = null;
  }

  public ReferenceResolutionException(
      String message, String reference, String availableReferences) {
    super(message);
    this.reference = reference;
    this.availableReferences = availableReferences;
  }
}
