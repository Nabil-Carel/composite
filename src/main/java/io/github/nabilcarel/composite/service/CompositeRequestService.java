package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
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
 * <p>The {@link io.github.nabilcarel.composite.controller.CompositeController controller}
 * calls {@link #execute} with an accepted request. The service validates it, starts the
 * batch, and calls {@link #forwardSubrequest} as each sub-request becomes ready.
 *
 * @see CompositeRequestServiceImpl
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
     * @param context        data captured for this batch on the request thread
     * @return a {@link Mono}&lt;{@link Void}&gt; that completes when the sub-request has
     *         been processed (regardless of success or failure)
     */
    Mono<Void> forwardSubrequest(
            SubRequest subRequest,
            String requestId,
            SubRequestContext context
    );

    /**
     * Validates and executes an accepted composite request.
     *
     * <p>An invalid request completes immediately with {@code 400 Bad Request} and runs
     * nothing. If {@code composite.request-timeout} elapses, in-flight calls are cancelled,
     * unstarted sub-requests are not dispatched, and the result is
     * {@code 504 Gateway Timeout}. Cancelling a call does not stop a handler that is already
     * running, so a non-idempotent endpoint may still finish after the timeout.
     *
     * <p>Cancelling the returned future cancels the batch in the same way. Embedded Tomcat does
     * not report a client disconnect while an async request is waiting, so on Tomcat a
     * disconnected batch runs until it finishes or reaches the timeout.
     *
     * @param compositeRequest the parsed composite request
     * @param request          the current servlet request, read only on the calling thread
     *                         to capture forwarded headers and the security context
     * @param response         the current servlet response
     * @return a {@link CompletableFuture} that resolves to a {@link ResponseEntity} wrapping
     *         the {@link CompositeResponse}
     */
    CompletableFuture<ResponseEntity<CompositeResponse>> execute(CompositeRequest compositeRequest,
                                                                 HttpServletRequest request,
                                                                 HttpServletResponse response);

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
