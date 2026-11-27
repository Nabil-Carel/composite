package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;

/**
 * Core service responsible for executing a composite request.
 *
 * <p>The {@link io.github.nabilcarel.composite.controller.CompositeController controller}
 * calls {@link #execute} with an accepted request. The service validates it, starts the
 * batch, and sends each ready sub-request through a
 * {@link io.github.nabilcarel.composite.dispatch.SubRequestDispatcher}.
 *
 * @see CompositeRequestServiceImpl
 * @see io.github.nabilcarel.composite.controller.CompositeController
 * @since 0.0.1
 */
public interface CompositeRequestService {

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
