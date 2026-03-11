package io.github.nabilcarel.composite.exception;

/**
 * Thrown when the response body of a loopback sub-request cannot be deserialised into the
 * type declared by
 * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint#value()
 * &#64;CompositeEndpoint}.
 *
 * @see io.github.nabilcarel.composite.service.CompositeRequestService
 * @since 0.0.1
 */
public class ResponseParsingException extends RuntimeException {

    /**
     * Creates a new {@code ResponseParsingException} with the given detail message and
     * underlying cause.
     *
     * @param message a description of the parsing failure
     * @param cause   the underlying deserialisation exception
     */
    public ResponseParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
