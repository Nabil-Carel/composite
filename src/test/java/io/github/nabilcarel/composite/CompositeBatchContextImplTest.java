package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.SubRequestCoordinator;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.CompositeBatchContextImpl;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private final SubRequestContext requestContext = new SubRequestContext(new HttpHeaders(), null);
    private Map<String, SubRequest> requestMap;
    private static final String BATCH_ID = "test-batch-id";

    @BeforeEach
    void setUp() {
        requestMap = new HashMap<>();
    }

    @Test
    void constructor_setsOnSubRequestResolvedCallback() {
        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

        verify(tracker).setOnSubRequestResolved(any());
    }

    @Test
    void startInitialRequests_forwardsReadySubRequests() {
        SubRequest subRequest = createSubRequest("a", "/api/a", "GET");
        requestMap.put("a", subRequest);

        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("a"));
        when(coordinator.markInProgress("a")).thenReturn(true);
        when(requestService.forwardSubrequest(eq(subRequest), eq(BATCH_ID), eq(requestContext)))
                .thenReturn(Mono.empty());

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

        batchContext.startInitialRequests();

        verify(requestService).forwardSubrequest(subRequest, BATCH_ID, requestContext);
    }

    @Test
    void startInitialRequests_skipsSubRequestsThatFailToMarkInProgress() {
        SubRequest subRequest = createSubRequest("a", "/api/a", "GET");
        requestMap.put("a", subRequest);

        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("a"));
        when(coordinator.markInProgress("a")).thenReturn(false);

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

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
        when(requestService.forwardSubrequest(any(), eq(BATCH_ID), eq(requestContext)))
                .thenReturn(Mono.empty());

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

        batchContext.startInitialRequests();

        verify(requestService).forwardSubrequest(reqA, BATCH_ID, requestContext);
        verify(requestService).forwardSubrequest(reqB, BATCH_ID, requestContext);
    }

    @Test
    void handleSubRequestResolved_forwardsNewlyReadyRequests() {
        SubRequest reqB = createSubRequest("b", "/api/b", "GET");
        requestMap.put("b", reqB);

        ArgumentCaptor<Consumer<String>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);

        when(coordinator.markResolved("a")).thenReturn(List.of("b"));
        when(coordinator.markInProgress("b")).thenReturn(true);
        when(tracker.getSubResponseMap()).thenReturn(Map.of());
        when(requestService.forwardSubrequest(eq(reqB), eq(BATCH_ID), eq(requestContext)))
                .thenReturn(Mono.empty());

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

        verify(tracker).setOnSubRequestResolved(callbackCaptor.capture());
        callbackCaptor.getValue().accept("a");

        verify(requestService).forwardSubrequest(reqB, BATCH_ID, requestContext);
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

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

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

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

        verify(tracker).setOnSubRequestResolved(callbackCaptor.capture());
        callbackCaptor.getValue().accept("a");

        verify(requestService, never()).forwardSubrequest(any(), any(), any());
    }

    @Test
    void cancel_preventsDependentDispatch() {
        ArgumentCaptor<Consumer<String>> callbackCaptor = ArgumentCaptor.forClass(Consumer.class);
        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);
        verify(tracker).setOnSubRequestResolved(callbackCaptor.capture());

        batchContext.cancel();
        callbackCaptor.getValue().accept("a");

        verify(coordinator, never()).markResolved(any());
        verify(requestService, never()).forwardSubrequest(any(), any(), any());
    }

    @Test
    void startInitialRequests_startsOnlyUpToTheLimit_thenDrainsAsCallsFinish() {
        List<String> ids = List.of("a", "b", "c");
        Map<String, Sinks.Empty<Void>> calls = new HashMap<>();
        for (String id : ids) {
            SubRequest subRequest = createSubRequest(id, "/api/" + id, "GET");
            requestMap.put(id, subRequest);
            Sinks.Empty<Void> call = Sinks.empty();
            calls.put(id, call);
            when(requestService.forwardSubrequest(subRequest, BATCH_ID, requestContext)).thenReturn(call.asMono());
        }
        when(coordinator.getInitialReadySubRequests()).thenReturn(ids);
        when(coordinator.markInProgress(anyString())).thenReturn(true);

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 1)
                .startInitialRequests();

        verify(requestService, times(1)).forwardSubrequest(any(), any(), any());

        calls.get("a").tryEmitEmpty();
        verify(requestService, times(2)).forwardSubrequest(any(), any(), any());

        calls.get("b").tryEmitEmpty();
        verify(requestService, times(3)).forwardSubrequest(any(), any(), any());
    }

    @Test
    void cancel_stopsQueuedSubRequestsAndCancelsTheRunningCall() {
        SubRequest reqA = createSubRequest("a", "/api/a", "GET");
        SubRequest reqB = createSubRequest("b", "/api/b", "GET");
        requestMap.put("a", reqA);
        requestMap.put("b", reqB);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("a", "b"));
        when(coordinator.markInProgress(anyString())).thenReturn(true);
        when(requestService.forwardSubrequest(reqA, BATCH_ID, requestContext))
                .thenReturn(Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 1);
        batchContext.startInitialRequests();
        batchContext.cancel();

        assertThat(cancelled).isTrue();
        verify(requestService, never()).forwardSubrequest(eq(reqB), any(), any());
    }

    @Test
    void dispatchFailure_isRecorded_andDoesNotStrandQueuedWork() {
        SubRequest reqA = createSubRequest("a", "/api/a", "GET");
        SubRequest reqB = createSubRequest("b", "/api/b", "GET");
        requestMap.put("a", reqA);
        requestMap.put("b", reqB);
        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("a", "b"));
        when(coordinator.markInProgress(anyString())).thenReturn(true);
        when(requestService.forwardSubrequest(reqA, BATCH_ID, requestContext))
                .thenThrow(new IllegalStateException("boom"));
        when(requestService.forwardSubrequest(reqB, BATCH_ID, requestContext)).thenReturn(Mono.empty());

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 1)
                .startInitialRequests();

        ArgumentCaptor<SubResponse> response = ArgumentCaptor.forClass(SubResponse.class);
        verify(tracker).addResponse(eq("a"), response.capture());
        assertThat(response.getValue().getHttpStatus()).isEqualTo(500);
        verify(requestService).forwardSubrequest(reqB, BATCH_ID, requestContext);
    }

    @Test
    void stopOnFailure_marksRunningAndQueuedWorkDifferently() {
        SubRequest failed = createSubRequest("failed", "/api/failed", "GET");
        SubRequest running = createSubRequest("running", "/api/running", "GET");
        SubRequest queued = createSubRequest("queued", "/api/queued", "GET");
        requestMap.put("failed", failed);
        requestMap.put("running", running);
        requestMap.put("queued", queued);
        Map<String, SubResponse> responses = new HashMap<>();
        responses.put("failed", SubResponse.builder().referenceId("failed").httpStatus(500).build());
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(tracker.getSubResponseMap()).thenReturn(responses);
        when(coordinator.getInitialReadySubRequests()).thenReturn(List.of("running", "queued"));
        when(coordinator.markInProgress(anyString())).thenReturn(true);
        when(requestService.forwardSubrequest(running, BATCH_ID, requestContext))
                .thenReturn(Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));

        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 1, true);
        verify(tracker).setOnSubRequestResolved(callback.capture());
        batchContext.startInitialRequests();
        callback.getValue().accept("failed");

        ArgumentCaptor<SubResponse> runningResponse = ArgumentCaptor.forClass(SubResponse.class);
        ArgumentCaptor<SubResponse> queuedResponse = ArgumentCaptor.forClass(SubResponse.class);
        verify(tracker).addResponse(eq("running"), runningResponse.capture());
        verify(tracker).addResponse(eq("queued"), queuedResponse.capture());
        assertThat(cancelled).isTrue();
        assertThat(runningResponse.getValue().getHttpStatus()).isEqualTo(503);
        assertThat(runningResponse.getValue().getBody().toString()).contains("may have already");
        assertThat(queuedResponse.getValue().getHttpStatus()).isEqualTo(424);
        assertThat(queuedResponse.getValue().getBody().toString()).contains("Not executed");
        verify(requestService, never()).forwardSubrequest(eq(queued), any(), any());
        verify(tracker, never()).addResponse(eq("failed"), any());
    }

    @Test
    void stopOnFailure_ignoresSuccessfulResponses() {
        SubRequest reqB = createSubRequest("b", "/api/b", "GET");
        requestMap.put("a", createSubRequest("a", "/api/a", "GET"));
        requestMap.put("b", reqB);
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        when(tracker.getSubResponseMap())
                .thenReturn(Map.of("a", SubResponse.builder().referenceId("a").httpStatus(200).build()));
        when(coordinator.markResolved("a")).thenReturn(List.of("b"));
        when(coordinator.markInProgress("b")).thenReturn(true);
        when(requestService.forwardSubrequest(reqB, BATCH_ID, requestContext)).thenReturn(Mono.empty());

        new CompositeBatchContextImpl(tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5, true);
        verify(tracker).setOnSubRequestResolved(callback.capture());
        callback.getValue().accept("a");

        verify(requestService).forwardSubrequest(reqB, BATCH_ID, requestContext);
    }

    @Test
    void constructor_rejectsInvalidConcurrencyLimit() {
        assertThatThrownBy(() -> new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getTracker_returnsTrackerInstance() {
        CompositeBatchContextImpl batchContext = new CompositeBatchContextImpl(
                tracker, coordinator, requestMap, requestService, BATCH_ID, requestContext, 5);

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
