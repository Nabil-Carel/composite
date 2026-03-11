package io.github.nabilcarel.composite.exception;

/**
 * Internal signal thrown by the reference resolver when a placeholder expression cannot
 * yet be resolved because the referenced sub-request has not completed.
 *
 * <p>This exception is used as a control-flow mechanism within the multi-pass resolution
 * loop: an unresolved placeholder is left in-place and the exception is caught silently,
 * allowing the resolver to attempt resolution again in the next pass once the referenced
 * sub-request has finished.
 *
 * <p>This is an internal exception and is not propagated to callers of the composite
 * execution endpoint.
 *
 * @see io.github.nabilcarel.composite.service.ReferenceResolverService
 * @since 0.0.1
 */
public class UnresolvedReferenceException extends RuntimeException {

    /**
     * Creates a new {@code UnresolvedReferenceException} with the given detail message.
     *
     * @param message a description of the unresolved expression
     */
    public UnresolvedReferenceException(String message) {
        super(message);
    }
}
