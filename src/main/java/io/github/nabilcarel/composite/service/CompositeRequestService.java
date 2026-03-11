package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

/**
 * Core service responsible for executing a composite request and dispatching individual
 * sub-requests via the loopback {@link org.springframework.web.reactive.function.client.WebClient}.
 *
 * <p>The composite execution pipeline is split between two layers:
 * <ul>
 *   <li>The {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter filter}
 *       orchestrates the dependency graph and calls {@link #forwardSubrequest} as each
 *       sub-request becomes ready.</li>
 *   <li>The {@link io.github.nabilcarel.composite.controller.CompositeController controller}
 *       calls {@link #execute} to block on the {@link CompletableFuture} that the filter
 *       started and return the aggregated result.</li>
 * </ul>
 *
 * @see CompositeRequestServiceImpl
 * @see io.github.nabilcarel.composite.config.filter.CompositeRequestFilter
 * @see io.github.nabilcarel.composite.controller.CompositeController
 * @since 0.0.1
 */
public interface CompositeRequestService {

    /**
     * Resolves placeholders in the sub-request, validates the resolved URL against the
     * endpoint registry, and dispatches the HTTP call via the loopback WebClient.
     *
     * <p>On completion (success or error) the result is deposited into the
     * {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker}
     * associated with {@code requestId}, which may in turn unlock dependent sub-requests.
     *
     * @param subRequest     the enriched sub-request to dispatch; must not be {@code null}
     * @param requestId      the UUID identifying the parent composite request; must not be
     *                       {@code null}
     * @param servletRequest the original incoming {@link HttpServletRequest}, used to
     *                       forward authentication headers and to read debug attributes
     * @return a {@link Mono}&lt;{@link Void}&gt; that completes when the sub-request has
     *         been processed (regardless of success or failure)
     */
    Mono<Void> forwardSubrequest(
            SubRequest subRequest,
            String requestId,
            HttpServletRequest servletRequest
    );

    /**
     * Awaits the completion of the composite request identified by the {@code requestId}
     * attribute set on {@code request}, then assembles and returns the
     * {@link CompositeResponse}.
     *
     * <p>If validation errors were recorded by the filter (indicated by the
     * {@code hasErrors} attribute), the method returns immediately with a
     * {@code 400 Bad Request} response.
     *
     * @param request  the current servlet request, carrying composite metadata as attributes
     * @param response the current servlet response, used for cleanup
     * @return a {@link CompletableFuture} that resolves to a {@link ResponseEntity} wrapping
     *         the {@link CompositeResponse}
     */
    CompletableFuture<ResponseEntity<CompositeResponse>> execute(HttpServletRequest request, HttpServletResponse response);

    /**
     * Returns the set of endpoints currently registered for composite execution.
     *
     * <p>Delegates to the
     * {@link io.github.nabilcarel.composite.config.EndpointRegistry EndpointRegistry}.
     * Intended for use by the discovery endpoint
     * ({@code GET {basePath}/endpoints}).
     *
     * @return the available composite endpoints; never {@code null}
     */
    Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints();
}
