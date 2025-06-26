package com.example.composite.service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

import com.example.composite.model.ResponseTracker;
import com.example.composite.model.request.CompositeRequestWrapper;
import com.example.composite.model.response.SubResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.example.composite.config.EndpointRegistry;
import com.example.composite.config.EndpointRegistry.EndpointInfo;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.request.SubRequestDto;
import com.example.composite.model.request.SubRequestWrapper;
import com.example.composite.model.response.SubResponseWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompositeRequestService {
    private final ServletContext servletContext;
    private final EndpointRegistry endpointRegistry;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResponseTracker> responseStore;

    public void forwardSubrequest(HttpServletRequest request, SubRequestDto subRequestDto,
            HttpServletResponse response, SecurityContext securityContext, String requestId) throws ServletException, IOException{
         forwardSubrequest(new SubRequest(subRequestDto), request, response, securityContext, requestId);
    }

    private void forwardSubrequest(
            SubRequest subRequest,
            HttpServletRequest originalRequest,
            HttpServletResponse originalResponse,
            SecurityContext securityContext,
            String requestId
    ) throws ServletException, IOException {

        Optional<EndpointInfo> endpointInfo = endpointRegistry.getEndpointInformations(
                subRequest.getMethod().toUpperCase(), subRequest.getUrl()
        );

        if (endpointInfo.isEmpty()) {
            SubResponse errorResponse = SubResponse.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST.value())
                    .referenceId(subRequest.getReferenceId())
                    .body("Invalid endpoint received: " + subRequest.getUrl())
                    .build();

            responseStore
                    .get(requestId)
                    .addResponse(subRequest.getReferenceId(), errorResponse);
            log.error("Invalid endpoint received: {}", subRequest.getUrl());
            return;
        }

        SubResponseWrapper responseWrapper = new SubResponseWrapper(
                originalResponse, endpointInfo.get().getReturnClass(), subRequest.getReferenceId(), objectMapper
        );
            SubRequestWrapper subRequestWrapper = new SubRequestWrapper(originalRequest, subRequest, objectMapper);
            SecurityContextHolder.setContext(securityContext);
            RequestDispatcher dispatcher = servletContext.getRequestDispatcher(subRequestWrapper.getRequestURI());
            dispatcher.forward(subRequestWrapper, responseWrapper);

    }

    public void processRequest(
            CompositeRequestWrapper requestWrapper,
            HttpServletResponse response,
            SecurityContext securityContext,
            String requestId
    ) throws IOException, ServletException {
        // Map referenceId to SubRequestDto
        Map<String, SubRequest> subRequestMap = new HashMap<>();
        // Map referenceId to set of dependencies
        Map<String, Set<String>> dependencies = new HashMap<>();

        for (SubRequestDto dto : requestWrapper.getBody().getSubRequests()) {
            SubRequest req = new SubRequest(dto);
            subRequestMap.put(dto.getReferenceId(), req);
            dependencies.put(dto.getReferenceId(),
                    new HashSet<>(Optional.ofNullable(req.getDependencies()).orElse(Set.of())));
        }

        // Process sub-requests with no dependencies first
        for (SubRequest request: subRequestMap.values()) {
            if (request.getDependencies() == null || request.getDependencies().isEmpty()) {
                forwardSubrequest(
                        requestWrapper,
                        request.getSubRequestDto(),
                        response,
                        securityContext,
                        requestId
                );
            }
            else {
            }

        }


    }
}
