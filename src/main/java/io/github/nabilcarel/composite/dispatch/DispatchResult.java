package io.github.nabilcarel.composite.dispatch;

import org.springframework.http.MediaType;

/**
 * The raw HTTP result of a dispatched sub-request.
 *
 * @param status the HTTP status code
 * @param contentType the response content type, or {@code null} if unknown
 * @param body the response body text, or {@code null} if there is none
 * @since 0.1.0
 */
public record DispatchResult(int status, MediaType contentType, String body) {
}
