package io.github.nabilcarel.composite.model.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.*;

/**
 * The top-level request payload submitted to the composite execution endpoint.
 *
 * <p>A {@code CompositeRequest} bundles one or more {@link SubRequestDto sub-requests}
 * into a single HTTP call. The composite runtime analyses the {@code ${referenceId.property}}
 * placeholder expressions declared in each sub-request to build a dependency graph, then
 * executes the sub-requests in topological order — running all dependency-free requests in
 * parallel while waiting for each group to complete before dispatching the next wave.
 *
 * <h2>Example payload</h2>
 * <pre class="code">
 * POST /api/composite/execute
 * {
 *   "subRequests": [
 *     {
 *       "referenceId": "user",
 *       "method": "GET",
 *       "url": "/api/users/42"
 *     },
 *     {
 *       "referenceId": "order",
 *       "method": "POST",
 *       "url": "/api/orders",
 *       "body": { "userId": "${user.id}", "email": "${user.email}" }
 *     }
 *   ]
 * }
 * </pre>
 *
 * @see SubRequestDto
 * @see io.github.nabilcarel.composite.model.response.CompositeResponse
 * @since 0.0.1
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming
public class CompositeRequest {

    /**
     * The ordered list of sub-requests to execute.
     *
     * <p>At least one entry is required. Each {@link SubRequestDto} must carry a unique
     * {@link SubRequestDto#getReferenceId() referenceId} within this list.
     */
    @NotEmpty(message = "At least one sub-request is required")
    private List<@Valid SubRequestDto> subRequests;

    /**
     * When {@code true}, the first non-2xx sub-response stops the rest of the batch.
     *
     * <p>Sub-requests that never started receive {@code 424}. In-flight calls are cancelled
     * and receive {@code 503}, because their handlers may have already run. This is not a
     * transaction: completed work is not rolled back. Defaults to {@code false}.
     */
    private boolean stopOnFailure;

    /**
     * Rejects unknown fields regardless of the host application's Jackson settings, so a
     * misspelled or unsupported option cannot be silently ignored.
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown composite request field: " + name + ". Remove it or check its spelling.");
    }
}
