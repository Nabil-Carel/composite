package io.github.nabilcarel.composite.exception;

import lombok.Getter;

/**
 * Thrown when a {@code ${referenceId.propertyPath}} placeholder expression cannot be
 * resolved because the referenced sub-request response or property is unavailable.
 *
 * <p>The {@link #reference} field carries the unresolvable reference ID, and
 * {@link #availableReferences} (when present) lists the reference IDs that <em>are</em>
 * available to aid debugging.
 *
 * @see io.github.nabilcarel.composite.service.ReferenceResolverService
 * @since 0.0.1
 */
@Getter
public class ReferenceResolutionException extends RuntimeException {

    /** The reference ID or property path that could not be resolved. */
    private final String reference;

    /**
     * A comma-separated string of the reference IDs available at the time of failure, or
     * {@code null} when not applicable.
     */
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
