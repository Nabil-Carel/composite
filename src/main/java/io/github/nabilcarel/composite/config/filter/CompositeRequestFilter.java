package io.github.nabilcarel.composite.config.filter;

import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.ResponseTrackerImpl;
import io.github.nabilcarel.composite.model.SubRequestCoordinator;
import io.github.nabilcarel.composite.model.SubRequestCoordinatorImpl;
import io.github.nabilcarel.composite.model.request.CompositeRequestWrapper;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.service.CompositeBatchContext;
import io.github.nabilcarel.composite.service.CompositeBatchContextImpl;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import io.github.nabilcarel.composite.service.CompositeRequestValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
@Slf4j
public class CompositeRequestFilter implements Filter {
    private final ApplicationContext context;
    private final CompositeRequestValidator compositeRequestValidator;
    @Qualifier("compositeObjectMapper")
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResponseTracker> responseStore;
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