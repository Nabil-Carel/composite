package io.github.nabilcarel.composite.config.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.ResponseTrackerImpl;
import io.github.nabilcarel.composite.model.SubRequestCoordinator;
import io.github.nabilcarel.composite.model.SubRequestCoordinatorImpl;
import io.github.nabilcarel.composite.model.request.CompositeRequestWrapper;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeDebugInfo;
import io.github.nabilcarel.composite.service.CompositeBatchContext;
import io.github.nabilcarel.composite.service.CompositeBatchContextImpl;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import io.github.nabilcarel.composite.service.CompositeRequestValidator;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Servlet {@link Filter} that intercepts composite execution requests before they reach the
 * {@link io.github.nabilcarel.composite.controller.CompositeController controller} and
 * performs all pre-processing necessary to execute the composite request asynchronously.
 *
 * <p>The filter is registered by
 * {@link io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration} with
 * the URL pattern configured by {@code composite.filter-pattern} and runs at
 * {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}
 * minus one so it executes very late in the filter chain.
 *
 * <h2>Processing steps</h2>
 * <ol>
 *   <li>Wraps the servlet request in a
 *       {@link io.github.nabilcarel.composite.model.request.CompositeRequestWrapper
 *       CompositeRequestWrapper} to cache the request body (enabling it to be read twice —
 *       once by the filter and once by the controller).</li>
 *   <li>Validates the deserialized {@link io.github.nabilcarel.composite.model.request.CompositeRequest
 *       CompositeRequest} via the
 *       {@link io.github.nabilcarel.composite.service.CompositeRequestValidator validator};
 *       sets the {@code hasErrors} and {@code errors} request attributes accordingly.</li>
 *   <li>If validation passes:
 *     <ul>
 *       <li>Assigns a UUID {@code requestId} and registers a
 *           {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker}
 *           in the shared response store.</li>
 *       <li>Builds a dependency map from the extracted sub-request placeholders.</li>
 *       <li>Optionally initialises a
 *           {@link io.github.nabilcarel.composite.model.response.CompositeDebugInfo
 *           CompositeDebugInfo} attribute when debug mode is enabled.</li>
 *       <li>Creates a
 *           {@link io.github.nabilcarel.composite.service.CompositeBatchContext
 *           CompositeBatchContext} and calls
 *           {@link io.github.nabilcarel.composite.service.CompositeBatchContext#startInitialRequests()
 *           startInitialRequests()} to fire the first wave of sub-requests.</li>
 *     </ul>
 *   </li>
 *   <li>Passes the wrapped request down the filter chain to the controller.</li>
 * </ol>
 *
 * <p>The {@link io.github.nabilcarel.composite.service.CompositeRequestService
 * CompositeRequestService} is resolved lazily from the
 * {@link org.springframework.context.ApplicationContext ApplicationContext} on first use to
 * avoid circular dependency issues during application startup.
 *
 * @see io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration
 * @see io.github.nabilcarel.composite.service.CompositeBatchContext
 * @since 0.0.1
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class CompositeRequestFilter implements Filter {
    private final ApplicationContext context;
    private final CompositeRequestValidator compositeRequestValidator;
    @Qualifier("compositeObjectMapper")
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResponseTracker> responseStore;
    private final CompositeProperties properties;
    private final AtomicReference<CompositeRequestService> serviceRef = new AtomicReference<>();

    private CompositeRequestService compositeRequestService;

    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
        throws IOException, ServletException {
        if(serviceRef.get() == null)
        {
            synchronized (this) {
                if(serviceRef.get() == null)
                {
                    serviceRef.set(context.getBean(CompositeRequestService.class));
                    compositeRequestService = serviceRef.get();
                }
            }
        }

        CompositeRequestWrapper request = new CompositeRequestWrapper((HttpServletRequest) servletRequest, objectMapper);

        // Validate the request
        List<String> errors = compositeRequestValidator.validateRequest(request.getBody());
        servletRequest.setAttribute("hasErrors", !errors.isEmpty());
        String requestId = UUID.randomUUID().toString();
        servletRequest.setAttribute("requestId", requestId);
        servletRequest.setAttribute("composite", true);

        if (errors.isEmpty()) {
            ResponseTracker tracker = new ResponseTrackerImpl(request.getBody().getSubRequests().size());
            responseStore.put(requestId, tracker);
            Map<String, SubRequest> requestMap = getRequestMap(request.getBody().getSubRequests());
            Map<String, Set<String>> dependencyMap = getDependencyMap(requestMap);

            if (properties.isDebugEnabled()) {
                CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                        .dependencyGraph(dependencyMap)
                        .resolvedRequests(new ConcurrentHashMap<>())
                        .build();
                servletRequest.setAttribute("compositeDebug", debugInfo);
            }

            SubRequestCoordinator requestCoordinator = new SubRequestCoordinatorImpl(dependencyMap);
            CompositeBatchContext batchContext = new CompositeBatchContextImpl(
                tracker,
                requestCoordinator,
                requestMap,
                compositeRequestService,
                requestId,
                request
            );

            batchContext.startInitialRequests();
        }
        else {
            servletRequest.setAttribute("errors", errors);
        }

        // Continue the filter chain to the controller
        filterChain.doFilter(request, servletResponse);
    }

    private Map<String, SubRequest> getRequestMap(List<SubRequestDto> requests) {
        return requests.stream()
                .collect(Collectors.toMap(
                        SubRequestDto::getReferenceId,
                        SubRequest::new
                ));
    }

    private Map<String, Set<String>> getDependencyMap(Map<String, SubRequest> requests) {
        return requests.values().stream()
                .collect(Collectors.toMap(
                        SubRequest::getReferenceId,
                        SubRequest::getDependencies
                ));
    }
}