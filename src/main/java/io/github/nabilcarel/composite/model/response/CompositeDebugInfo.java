package io.github.nabilcarel.composite.model.response;

import java.util.Map;
import java.util.Set;
import lombok.*;

/**
 * Debug information appended to a {@link CompositeResponse} when
 * {@code composite.debug-enabled} is {@code true}.
 *
 * <p>Exposes the resolved dependency graph and, for each sub-request, the original and
 * resolved URL/body values. This is intended exclusively for development and troubleshooting
 * and <strong>must not be enabled in production</strong> as it may expose internal
 * implementation details.
 *
 * @see CompositeResponse
 * @see io.github.nabilcarel.composite.config.CompositeProperties
 * @since 0.0.1
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeDebugInfo {

    /**
     * The dependency graph computed from placeholder expressions in the composite request.
     *
     * <p>Maps each {@code referenceId} to the set of {@code referenceId}s it depends on.
     * Sub-requests with no dependencies map to an empty set.
     */
    private Map<String, Set<String>> dependencyGraph;

    /**
     * Per-sub-request debug information, keyed by {@code referenceId}.
     *
     * <p>Populated as each sub-request is resolved; only entries for dispatched sub-requests
     * are present.
     */
    private Map<String, SubRequestDebugInfo> resolvedRequests;

    /**
     * Captures the before-and-after state of a single sub-request's URL and body after
     * placeholder resolution.
     *
     * @since 0.0.1
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubRequestDebugInfo {

        /** The URL as declared in the original {@link io.github.nabilcarel.composite.model.request.SubRequestDto}. */
        private String originalUrl;

        /** The URL after all {@code ${...}} placeholders have been resolved. */
        private String resolvedUrl;

        /**
         * A deep copy of the request body before placeholder resolution.
         * {@code null} for requests that carry no body.
         */
        private Object originalBody;

        /** The request body after placeholder resolution. */
        private Object resolvedBody;
    }
}
