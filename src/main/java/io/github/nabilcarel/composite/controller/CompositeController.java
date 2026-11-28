package io.github.nabilcarel.composite.controller;

import io.github.nabilcarel.composite.config.CompositeProperties;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * Built-in REST controller that exposes the composite execution and endpoint discovery
 * endpoints.
 *
 * <p>The controller is registered only when {@code composite.controller.enabled} is
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
 *     <td>Returns the set of endpoints currently registered for composite execution, when
 *         {@code composite.controller.list-endpoints=true}.</td>
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
    /** Lets the batch deadline produce its response before the container's async timeout. */
    private static final long ASYNC_TIMEOUT_MARGIN_MILLIS = 1_000;

    private final CompositeRequestService requestService;
    private final CompositeProperties properties;

    /**
     * Executes a composite request.
     *
     * <p>Spring MVC has already authenticated the request, negotiated its content type and
     * bound its body by the time this method runs, so a rejected request has no side
     * effects. The servlet thread is released while the batch runs.
     *
     * @param request     the current servlet request, used to capture forwarded headers
     * @param response    the current servlet response
     * @param requestBody the composite request
     * @return a {@link DeferredResult} resolving to the composite response
     */
    @PostMapping(value = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<ResponseEntity<CompositeResponse>> execute(HttpServletRequest request,
                                                                     HttpServletResponse response,
                                                                     @RequestBody CompositeRequest requestBody) {
        DeferredResult<ResponseEntity<CompositeResponse>> result = new DeferredResult<>(
                properties.getRequestTimeout().toMillis() + ASYNC_TIMEOUT_MARGIN_MILLIS);

        CompletableFuture<ResponseEntity<CompositeResponse>> execution =
                requestService.execute(requestBody, request, response);

        // Fires on normal completion too, where cancelling a finished future has no effect.
        result.onCompletion(() -> execution.cancel(true));

        execution.whenComplete((compositeResponse, failure) -> {
            if (failure != null) {
                result.setErrorResult(failure);
            } else {
                result.setResult(compositeResponse);
            }
        });

        return result;
    }

    /**
     * Returns the set of endpoints currently registered for composite execution.
     *
     * <p>Intended for use during development to discover which of your controller methods
     * have been annotated with
     * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint &#64;CompositeEndpoint}
     * and are therefore reachable via the composite execute endpoint.
     *
     * <p>Answers {@code 404} unless {@code composite.controller.list-endpoints=true}.
     *
     * @return a set of {@link EndpointRegistry.EndpointInfo} descriptors; never {@code null}
     * @throws ResponseStatusException with {@code 404} when listing is disabled
     */
    @GetMapping("/endpoints")
    public Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints() {
        if (!properties.getController().isListEndpoints()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return requestService.getAvailableEndpoints();
    }
}
