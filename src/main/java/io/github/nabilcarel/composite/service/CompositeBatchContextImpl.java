package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.SubRequestCoordinator;
import io.github.nabilcarel.composite.model.request.SubRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CompositeBatchContextImpl implements CompositeBatchContext {
    @Getter
    private final ResponseTracker tracker;
    private final SubRequestCoordinator coordinator;
    private final Map<String, SubRequest> requestMap;
    private final CompositeRequestService requestService;
    private final String batchId;
    private final HttpServletRequest servletRequest;

    public CompositeBatchContextImpl(
            ResponseTracker tracker,
            SubRequestCoordinator coordinator,
            Map<String, SubRequest> requestMap,
            CompositeRequestService requestService,
            String batchId,
            HttpServletRequest servletRequest
    ) {
        this.tracker = tracker;
        this.coordinator = coordinator;
        this.requestMap = requestMap;
        this.requestService = requestService;
        this.batchId = batchId;
        this.servletRequest = servletRequest;

        tracker.setOnSubRequestResolved(this::handleSubRequestResolved);
    }

    private void handleSubRequestResolved(String resolvedId) {
        List<String> nowReady = coordinator.markResolved(resolvedId);
        List<Mono<Void>> monos = nowReady.stream()
                .filter(coordinator::markInProgress)
                .map(id -> requestService.forwardSubrequest(requestMap.get(id), batchId, servletRequest))
                .toList();
        Mono.when(monos).subscribe();
    }

    public void startInitialRequests()  {
        List<Mono<Void>> monos = coordinator.getInitialReadySubRequests().stream()
                .filter(coordinator::markInProgress)
                .map(id -> requestService.forwardSubrequest(requestMap.get(id), batchId, servletRequest))
                .toList();
        Mono.when(monos).subscribe();
    }
}
