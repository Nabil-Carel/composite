package io.github.nabilcarel.composite.internal;

import java.io.IOException;

/** Signals that a sub-response exceeded {@code composite.loopback.max-response-size}. */
public final class ResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    public ResponseTooLargeException(long limit) {
        super("Sub-response is larger than " + limit + " bytes");
    }
}
