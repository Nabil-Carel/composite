package com.example.composite.config.filter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.example.composite.model.ResponseTracker;
import com.example.composite.model.ResponseTrackerImpl;
import com.example.composite.model.SubRequestCoordinator;
import com.example.composite.model.SubRequestCoordinatorImpl;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.request.SubRequestDto;
import com.example.composite.service.CompositeBatchContext;
import com.example.composite.service.CompositeBatchContextImpl;
import com.example.composite.service.CompositeRequestValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.example.composite.model.request.CompositeRequestWrapper;
import com.example.composite.service.CompositeRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Component
@Slf4j
public class CompositeRequestFilter implements Filter {
    private final CompositeRequestService compositeRequestService;
    private final CompositeRequestValidator compositeRequestValidator;
    @Qualifier("compositeObjectMapper")
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResponseTracker> responseStore;

    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
        throws IOException, ServletException {
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
            SecurityContext originalSecurityContext = SecurityContextHolder.getContext();
            Map<String, SubRequest> requestMap = getRequestMap(request.getBody().getSubRequests());
            Map<String, Set<String>> dependencyMap = getDependencyMap(requestMap);
            SubRequestCoordinator requestCoordinator = new SubRequestCoordinatorImpl(dependencyMap);
            CompositeBatchContext batchContext = new CompositeBatchContextImpl(
                tracker,
                requestCoordinator,
                requestMap,
                (HttpServletRequest) servletRequest,
                (HttpServletResponse) servletResponse,
                compositeRequestService,
                originalSecurityContext,
                requestId
            );

            batchContext.startInitialRequests();
        }
        else {
            servletRequest.setAttribute("errors", errors);
        }

        // Continue the filter chain to the controller
        filterChain.doFilter(servletRequest, servletResponse);
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