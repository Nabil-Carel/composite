package com.example.composite.service;

import com.example.composite.model.ResponseTracker;
import com.example.composite.model.SubRequestCoordinator;
import com.example.composite.model.request.SubRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import org.springframework.security.core.context.SecurityContext;

import java.util.List;
import java.util.Map;

public class CompositeBatchContextImpl implements CompositeBatchContext {
    @Getter
    private final ResponseTracker tracker;
    private final SubRequestCoordinator coordinator;
    private final Map<String, SubRequest> requestMap;
    private final HttpServletRequest originalRequest;
    private final HttpServletResponse response;
    private final CompositeRequestService requestService;
    private final SecurityContext securityContext;
    private final String batchId;

    public CompositeBatchContextImpl(
            ResponseTracker tracker,
            SubRequestCoordinator coordinator,
            Map<String, SubRequest> requestMap,
            HttpServletRequest originalRequest,
            HttpServletResponse response,
            CompositeRequestService requestService,
            SecurityContext securityContext,
            String batchId
    ) {
        this.tracker = tracker;
        this.coordinator = coordinator;
        this.requestMap = requestMap;
        this.originalRequest = originalRequest;
        this.response = response;
        this.requestService = requestService;
        this.securityContext = securityContext;
        this.batchId = batchId;

        tracker.setOnSubRequestResolved(this::handleSubRequestResolved);
    }

    private void handleSubRequestResolved(String resolvedId) {
        List<String> nowReady = coordinator.markResolved(resolvedId);
        for (String id : nowReady) {
            if (coordinator.markInProgress(id)) {
                requestService.forwardSubrequest(requestMap.get(id), originalRequest,response, securityContext, batchId);
            }
        }
    }

    public void startInitialRequests()  {
        for (String id : coordinator.getInitialReadySubRequests()) {
            if (coordinator.markInProgress(id)) {
                requestService.forwardSubrequest(requestMap.get(id), originalRequest,response, securityContext, batchId);
            }
        }
    }
}
