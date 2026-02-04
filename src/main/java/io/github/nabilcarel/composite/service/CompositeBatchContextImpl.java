package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.SubRequestCoordinator;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.response.SubResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

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
                .map(id -> {
                    SubRequest subRequest = requestMap.get(id);
                    // Check if any dependency failed
                    if (hasFailedDependency(subRequest)) {
                        return createFailedDependencyResponse(id, subRequest);
                    }
                    return requestService.forwardSubrequest(subRequest, batchId, servletRequest);
                })
                .toList();
        Mono.when(monos).subscribe();
    }

    private boolean hasFailedDependency(SubRequest subRequest) {
        Set<String> dependencies = subRequest.getDependencies();
        Map<String, SubResponse> responses = tracker.getSubResponseMap();
        
        for (String depId : dependencies) {
            SubResponse depResponse = responses.get(depId);
            if (depResponse == null) {
                // Dependency not yet resolved - wait
                return false;
            }
            // Check if dependency failed (4xx or 5xx status)
            if (depResponse.getHttpStatus() >= 400) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> createFailedDependencyResponse(String subRequestId, SubRequest subRequest) {
        Set<String> failedDeps = subRequest.getDependencies().stream()
                .filter(depId -> {
                    SubResponse depResponse = tracker.getSubResponseMap().get(depId);
                    return depResponse != null && depResponse.getHttpStatus() >= 400;
                })
                .collect(java.util.stream.Collectors.toSet());
        
        SubResponse failedDependencyResponse = SubResponse.builder()
                .httpStatus(HttpStatus.FAILED_DEPENDENCY.value()) // Failed Dependency
                .referenceId(subRequestId)
                .body("Failed Dependency: One or more dependencies failed: " + String.join(", ", failedDeps))
                .build();
        
        tracker.addResponse(subRequestId, failedDependencyResponse);
        return Mono.empty();
    }

    public void startInitialRequests()  {
        List<Mono<Void>> monos = coordinator.getInitialReadySubRequests().stream()
                .filter(coordinator::markInProgress)
                .map(id -> requestService.forwardSubrequest(requestMap.get(id), batchId, servletRequest))
                .toList();
        Mono.when(monos).subscribe();
    }
}
