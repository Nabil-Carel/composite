package com.example.composite.config.filter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import com.example.composite.model.ResponseTracker;
import com.example.composite.service.CompositeRequestValidator;
import lombok.extern.slf4j.Slf4j;
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
            ResponseTracker tracker = new ResponseTracker(request.getBody().getSubRequests().size());
            responseStore.put(requestId, tracker);
            SecurityContext originalSecurityContext = SecurityContextHolder.getContext();

      /*  for (SubRequestDto subRequestDto : request.getBody().getSubRequests()) {
            compositeRequestService.forwardSubrequest(request,
                                                      subRequestDto,
                                                      (HttpServletResponse) servletResponse,
                                                      originalSecurityContext,
                                                      requestId);
        }*/

            compositeRequestService.processRequest(request,
                                                   (HttpServletResponse) servletResponse,
                                                   originalSecurityContext,
                                                   requestId);

        }
        else {
            servletRequest.setAttribute("errors", errors);
        }

        // Continue the filter chain to the controller
        filterChain.doFilter(servletRequest, servletResponse);
    }
}
