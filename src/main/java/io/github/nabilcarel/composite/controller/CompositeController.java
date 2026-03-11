package io.github.nabilcarel.composite.controller;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Built-in REST controller that exposes the composite execution and endpoint discovery
 * endpoints.
 *
 * <p>The controller is registered only when {@code composite.controller-enabled} is
 * {@code true} (the default). Set it to {@code false} to replace this controller with
 * your own — for example, to add custom security annotations or a different response
 * envelope.
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <caption>Composite REST endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code POST}</td>
 *     <td>{@code {basePath}/execute}</td>
 *     <td>Executes a {@link io.github.nabilcarel.composite.model.request.CompositeRequest
 *         CompositeRequest} and returns a
 *         {@link io.github.nabilcarel.composite.model.response.CompositeResponse
 *         CompositeResponse}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GET}</td>
 *     <td>{@code {basePath}/endpoints}</td>
 *     <td>Returns the set of endpoints currently registered for composite execution.</td>
 *   </tr>
 * </table>
 *
 * <p>The controller is marked {@code @Lazy} to avoid initialising the service layer until
 * the first request arrives.
 *
 * @see io.github.nabilcarel.composite.config.CompositeProperties
 * @see io.github.nabilcarel.composite.service.CompositeRequestService
 * @since 0.0.1
 */
@RestController
@Lazy
@RequestMapping("${composite.base-path:/api/composite}")
@ConditionalOnProperty(name = "composite.controller.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CompositeController {
    @Lazy
    private final CompositeRequestService requestService;

    /**
     * Executes a composite request.
     *
     * <p>The actual execution is driven by the
     * {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter filter},
     * which has already dispatched the sub-requests by the time this method is invoked. This
     * handler simply waits for the
     * {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker} future to
     * complete and returns the aggregated
     * {@link io.github.nabilcarel.composite.model.response.CompositeResponse}.
     *
     * <p>The {@code requestBody} parameter is declared solely to allow API documentation
     * tools (e.g. Springdoc / Swagger) to generate a correct request schema; the body is
     * consumed by the filter before this method is called.
     *
     * @param request     the current servlet request carrying composite metadata as
     *                    attributes set by the filter
     * @param response    the current servlet response
     * @param requestBody the composite request body (used for API documentation only)
     * @return a {@link CompletableFuture} resolving to the composite response
     */
    @PostMapping("/execute")
    public CompletableFuture<ResponseEntity<CompositeResponse>> execute(HttpServletRequest request,
                                                                        HttpServletResponse response,
                                                                        @RequestBody CompositeRequest requestBody /*for swagger*/) {
       return requestService.execute(request, response);
    }

    /**
     * Returns the set of endpoints currently registered for composite execution.
     *
     * <p>Intended for use during development to discover which of your controller methods
     * have been annotated with
     * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint &#64;CompositeEndpoint}
     * and are therefore reachable via the composite execute endpoint.
     *
     * @return a set of {@link EndpointRegistry.EndpointInfo} descriptors; never {@code null}
     */
    @GetMapping("/endpoints")
    public Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints() {
        return requestService.getAvailableEndpoints();
    }
}
