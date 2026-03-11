package io.github.nabilcarel.composite.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.*;

/**
 * The aggregated response returned by the composite execution endpoint.
 *
 * <p>A {@code CompositeResponse} collects the individual {@link SubResponse} entries produced
 * by each sub-request, keyed by their {@code referenceId}, together with a top-level error
 * summary and an optional debug payload.
 *
 * <h2>Inspecting results</h2>
 * <pre class="code">
 * CompositeResponse result = ...; // from POST /api/composite/execute
 *
 * if (result.isHasErrors()) {
 *     result.getErrors().forEach(System.err::println);
 * }
 *
 * SubResponse userResponse = result.getResponses().get("user");
 * if (userResponse.getHttpStatus() == 200) {
 *     User user = (User) userResponse.getBody();
 * }
 * </pre>
 *
 * @see SubResponse
 * @see CompositeDebugInfo
 * @see io.github.nabilcarel.composite.model.request.CompositeRequest
 * @since 0.0.1
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeResponse {

    /**
     * A map from {@code referenceId} to the corresponding {@link SubResponse}.
     *
     * <p>Contains one entry for every sub-request that was dispatched, including those that
     * resulted in an error. Sub-requests that were skipped due to a failed dependency will
     * have an entry with HTTP status {@code 424 Failed Dependency}.
     */
    private Map<String, SubResponse> responses;

    /**
     * {@code true} if at least one sub-request completed with a non-2xx HTTP status code.
     */
    private boolean hasErrors;

    /**
     * Human-readable error messages collected during validation or execution.
     *
     * <p>Populated when the composite request itself is invalid (e.g. circular dependencies,
     * unknown endpoints) or when an internal error prevents execution from starting. For
     * per-sub-request errors, inspect the individual {@link SubResponse#getHttpStatus()} and
     * {@link SubResponse#getBody()} fields in {@link #responses}.
     */
    private List<String> errors;

    /**
     * Optional debug information included when
     * {@link io.github.nabilcarel.composite.config.CompositeProperties#isDebugEnabled()
     * composite.debug-enabled} is {@code true}.
     *
     * <p>Always {@code null} in production environments. Contains the dependency graph and
     * the original-vs-resolved URL/body for each sub-request.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private CompositeDebugInfo debug;
}
