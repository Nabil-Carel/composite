package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.SubRequestCoordinator;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.CompositeBatchContextImpl;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeBatchContextImplTest {

    @Mock
    private ResponseTracker tracker;
    @Mock
    private SubRequestCoordinator coordinator;
    @Mock
    private CompositeRequestService requestService;
    @Mock
    private HttpServletRequest servletRequest;

    private Map<String, SubRequest> requestMap;
    private static final String BATCH_ID = "test-batch-id";

    @BeforeEach
    void setUp() {
        requestMap = new HashMap<>();
    }

    @Test
    void constructor_setsOnSubRequestResolvedCallback() {
        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        verify(tracker).setOnSubRequestResolved(any());
    }

    @Test
    void startInitialRequests_forwardsReadySubRequests() {
        SubRequest subRequest = createSubRequest("a", "/api/a", "GET");
        requestMap.put("a", subRequest);

        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("a"));
        when(coordinator.markInProgress("a")).thenReturn(true);
        when(requestService.forwardSubrequest(eq(subRequest), eq(BATCH_ID), eq(servletRequest)))
                .thenReturn(Mono.empty());

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        batchContext.startInitialRequests();

        verify(requestService).forwardSubrequest(subRequest, BATCH_ID, servletRequest);
    }

    @Test
    void startInitialRequests_skipsSubRequestsThatFailToMarkInProgress() {
        SubRequest subRequest = createSubRequest("a", "/api/a", "GET");
        requestMap.put("a", subRequest);

        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("a"));
        when(coordinator.markInProgress("a")).thenReturn(false);

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        batchContext.startInitialRequests();

        verify(requestService, never()).forwardSubrequest(any(), any(), any());
    }

    @Test
    void startInitialRequests_withMultipleReadyRequests_forwardsAll() {
        SubRequest reqA = createSubRequest("a", "/api/a", "GET");
        SubRequest reqB = createSubRequest("b", "/api/b", "GET");
        requestMap.put("a", reqA);
        requestMap.put("b", reqB);

        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("a", "b"));
        when(coordinator.markInProgress(anyString())).thenReturn(true);
        when(requestService.forwardSubrequest(any(), eq(BATCH_ID), eq(servletRequest)))
                .thenReturn(Mono.empty());

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        batchContext.startInitialRequests();

        verify(requestService).forwardSubrequest(reqA, BATCH_ID, servletRequest);
        verify(requestService).forwardSubrequest(reqB, BATCH_ID, servletRequest);
    }

    @Test
    void handleSubRequestResolved_forwardsNewlyReadyRequests() {
        SubRequest reqB = createSubRequest("b", "/api/b", "GET");
        requestMap.put("b", reqB);

        ArgumentCaptor<Consumer<String>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);

        when(coordinator.markResolved("a")).thenReturn(List.of("b"));
        when(coordinator.markInProgress("b")).thenReturn(true);
        when(tracker.getSubResponseMap()).thenReturn(Map.of());
        when(requestService.forwardSubrequest(eq(reqB), eq(BATCH_ID), eq(servletRequest)))
                .thenReturn(Mono.empty());

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        verify(tracker).setOnSubRequestResolved(callbackCaptor.capture());
        callbackCaptor.getValue().accept("a");

        verify(requestService).forwardSubrequest(reqB, BATCH_ID, servletRequest);
    }

    @Test
    void handleSubRequestResolved_withFailedDependency_createsFailedDependencyResponse() {
        SubRequest reqB = createSubRequestWithDependencies("b", "/api/b/${a.id}", "GET", Set.of("a"));
        requestMap.put("b", reqB);

        SubResponse failedResponse = SubResponse.builder()
                .referenceId("a").httpStatus(500).body("Server Error").build();

        ArgumentCaptor<Consumer<String>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);

        when(coordinator.markResolved("a")).thenReturn(List.of("b"));
        when(coordinator.markInProgress("b")).thenReturn(true);
        when(tracker.getSubResponseMap()).thenReturn(Map.of("a", failedResponse));

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        verify(tracker).setOnSubRequestResolved(callbackCaptor.capture());
        callbackCaptor.getValue().accept("a");

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(tracker).addResponse(eq("b"), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(424); // Failed Dependency
        assertThat(response.getBody().toString()).contains("Failed Dependency");
        verify(requestService, never()).forwardSubrequest(eq(reqB), any(), any());
    }

    @Test
    void handleSubRequestResolved_withNoNewlyReadyRequests_doesNotForward() {
        ArgumentCaptor<Consumer<String>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);

        when(coordinator.markResolved("a")).thenReturn(List.of());

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        verify(tracker).setOnSubRequestResolved(callbackCaptor.capture());
        callbackCaptor.getValue().accept("a");

        verify(requestService, never()).forwardSubrequest(any(), any(), any());
    }

    @Test
    void getTracker_returnsTrackerInstance() {
        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, servletRequest);

        assertThat(batchContext.getTracker()).isSameAs(tracker);
    }

    // ========== Helper Methods ==========

    private SubRequest createSubRequest(String refId, String url, String method) {
        SubRequestDto dto = SubRequestDto.builder()
                .referenceId(refId)
                .url(url)
                .method(method)
                .build();
        SubRequest subRequest = new SubRequest(dto);
        subRequest.setHeaders(new HashMap<>());
        return subRequest;
    }

    private SubRequest createSubRequestWithDependencies(String refId, String url, String method, Set<String> deps) {
        SubRequestDto dto = SubRequestDto.builder()
                .referenceId(refId)
                .url(url)
                .method(method)
                .build();
        SubRequest subRequest = new SubRequest(dto);
        subRequest.setHeaders(new HashMap<>());
        subRequest.setDependencies(deps);
        return subRequest;
    }
}
