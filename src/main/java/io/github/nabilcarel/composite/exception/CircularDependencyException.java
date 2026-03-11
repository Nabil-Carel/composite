package io.github.nabilcarel.composite.exception;

/**
 * Thrown when a circular dependency is detected in the sub-request dependency graph.
 *
 * <p>The composite request validator performs a depth-first-search cycle check on the
 * dependency graph before any sub-requests are dispatched. If a cycle is found, this
 * exception (or a validation error derived from it) is raised and the entire composite
 * request is rejected with a {@code 400 Bad Request} response.
 *
 * @see io.github.nabilcarel.composite.service.CompositeRequestValidator
 * @since 0.0.1
 */
public class CircularDependencyException extends RuntimeException {

    /**
     * Creates a new {@code CircularDependencyException} with the given detail message.
     *
     * @param message a description of the detected cycle
     */
    public CircularDependencyException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code CircularDependencyException} with the given detail message and
     * cause.
     *
     * @param message a description of the detected cycle
     * @param cause   the underlying cause
     */
    public CircularDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
