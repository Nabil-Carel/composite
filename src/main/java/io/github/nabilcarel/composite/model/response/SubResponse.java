package io.github.nabilcarel.composite.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;
import lombok.*;

/**
 * Represents the outcome of a single sub-request within a composite execution.
 *
 * <p>Each {@code SubResponse} is keyed by its {@code referenceId} in the responses map of
 * the enclosing {@link CompositeResponse}. It captures the HTTP status code, the response
 * body and selected response headers produced by the target endpoint.
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
     * <p>A JSON response, successful or not, is a {@link com.fasterxml.jackson.databind.JsonNode}.
     * Other responses, and errors recorded by the library, are a {@link String}. {@code null}
     * when the endpoint returned no body.
     */
    private Object body;

    /**
     * The response headers named by {@code composite.response-headers}, such as
     * {@code Location}. Several values of one header are joined with {@code ", "}. Omitted
     * from JSON when the endpoint sent none of them.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> headers;
}
