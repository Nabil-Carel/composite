package io.github.nabilcarel.composite.model.request;

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
     * When {@code true}, any sub-request failure causes the entire composite request to be
     * treated as failed.
     *
     * <p>Defaults to {@code false}, meaning partial success is allowed: completed
     * sub-requests are still returned alongside error entries for failed ones.
     */
    @Builder.Default
    private boolean allOrNone = false;
}
