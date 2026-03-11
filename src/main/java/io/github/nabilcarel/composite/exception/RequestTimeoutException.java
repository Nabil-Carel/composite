package io.github.nabilcarel.composite.exception;

import java.time.Duration;
import lombok.Getter;

/**
 * Thrown when a composite request or an individual sub-request exceeds its configured
 * timeout.
 *
 * <p>The {@link #timeout} field records the limit that was exceeded, and the optional
 * {@link #referenceId} identifies the sub-request that timed out (when applicable).
 *
 * @see io.github.nabilcarel.composite.config.CompositeProperties
 * @since 0.0.1
 */
@Getter
public class RequestTimeoutException extends RuntimeException {

    /** The configured timeout that was exceeded, or {@code null} when not available. */
    private final Duration timeout;

    /**
     * The {@code referenceId} of the sub-request that timed out, or {@code null} when the
     * timeout applies to the whole composite request.
     */
    private final String referenceId;

    public RequestTimeoutException(String message) {
        super(message);
        this.timeout = null;
        this.referenceId = null;
    }

    public RequestTimeoutException(String message, Duration timeout, String referenceId) {
        super(message);
        this.timeout = timeout;
        this.referenceId = referenceId;
    }
}
