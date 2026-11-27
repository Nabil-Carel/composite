package io.github.nabilcarel.composite.model.response;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.*;

/**
 * Represents the outcome of a single sub-request within a composite execution.
 *
 * <p>Each {@code SubResponse} is keyed by its {@code referenceId} in the responses map of
 * the enclosing {@link CompositeResponse}. It captures the HTTP status code and the response
 * body produced by the target endpoint.
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
@JsonNaming
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
     * The response body.
     *
     * <p>A successful JSON response is a {@link com.fasterxml.jackson.databind.JsonNode}.
     * Non-JSON and error responses are a {@link String}. {@code null} when the endpoint
     * returned no body.
     */
    private Object body;
}
