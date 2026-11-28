package io.github.nabilcarel.composite.dispatch;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The raw HTTP result of a dispatched sub-request.
 *
 * <p>Only the response headers named by {@code composite.response-headers} reach the client.
 *
 * @param status the HTTP status code
 * @param contentType the response content type, or {@code null} if unknown
 * @param body the response body text, or {@code null} if there is none
 * @param headers the response headers, never {@code null}
 * @since 0.1.0
 */
public record DispatchResult(int status, MediaType contentType, String body, HttpHeaders headers) {

    /**
     * Creates a result, replacing {@code null} headers with an empty set.
     */
    public DispatchResult {
        headers = headers == null ? HttpHeaders.EMPTY : HttpHeaders.readOnlyHttpHeaders(headers);
    }

    /**
     * Creates a result without response headers.
     *
     * @param status the HTTP status code
     * @param contentType the response content type, or {@code null} if unknown
     * @param body the response body text, or {@code null} if there is none
     */
    public DispatchResult(int status, MediaType contentType, String body) {
        this(status, contentType, body, null);
    }
}
