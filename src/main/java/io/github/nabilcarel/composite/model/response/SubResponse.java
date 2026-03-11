package io.github.nabilcarel.composite.model.response;

import java.util.Map;
import lombok.*;

/**
 * Represents the outcome of a single sub-request within a composite execution.
 *
 * <p>Each {@code SubResponse} is keyed by its {@code referenceId} in the responses map of
 * the enclosing {@link CompositeResponse}. It captures the HTTP status code, the
 * deserialized response body, and any response headers produced by the target endpoint.
 *
 * <p>When a sub-request's dependency fails, the sub-request itself is never dispatched;
 * instead a synthetic {@code SubResponse} with HTTP status
 * {@code 424 Failed Dependency} is recorded.
 *
 * @see CompositeResponse
 * @since 0.0.1
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubResponse {

    /**
     * The {@code referenceId} of the sub-request that produced this response.
     *
     * <p>Matches the {@link io.github.nabilcarel.composite.model.request.SubRequestDto#getReferenceId()
     * referenceId} declared in the original {@link io.github.nabilcarel.composite.model.request.SubRequestDto}.
     */
    private String referenceId;

    /**
     * The HTTP status code returned by the target endpoint, or a synthetic status code for
     * internally generated error entries (e.g. {@code 400}, {@code 424}, {@code 503}).
     */
    private int httpStatus;

    /**
     * The deserialized response body.
     *
     * <p>For successful (2xx) responses the body is an instance of the class declared via
     * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint#value()}.
     * For error responses it is a {@link String} containing the raw error body.
     * {@code null} for endpoints that declare {@link Void} as their return type.
     */
    private Object body;

    /**
     * The response headers returned by the target endpoint, as a flat string-to-string map.
     *
     * <p>Only the first value is captured for multi-valued headers.
     */
    private Map<String, String> headers;
}
