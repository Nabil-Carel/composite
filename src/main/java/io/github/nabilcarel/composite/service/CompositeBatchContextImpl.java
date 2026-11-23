package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.SubRequestCoordinator;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

/**
 * Default {@link CompositeBatchContext} implementation that wires the completion callback of
 * the {@link ResponseTracker} to the {@link SubRequestCoordinator}'s dependency resolution
 * logic.
 *
 * <p>On construction, registers {@link #handleSubRequestResolved(String)} as the callback
 * on the {@link ResponseTracker}. Each time a sub-request completes, the callback:
 * <ol>
 *   <li>Asks the {@link SubRequestCoordinator} which sub-requests are now ready
 *       ({@link SubRequestCoordinator#markResolved(String)}).</li>
 *   <li>Atomically marks each ready sub-request as {@code IN_PROGRESS}
 *       ({@link SubRequestCoordinator#markInProgress(String)}) to prevent concurrent
 *       double-dispatch.</li>
 *   <li>Checks whether any of the newly-ready sub-requests have failed dependencies; if so,
 *       a synthetic {@code 424 Failed Dependency} response is recorded immediately.</li>
 *   <li>Queues the remaining ready sub-requests and dispatches them via
 *       {@link CompositeRequestService#forwardSubrequest}, never exceeding the batch's
 *       concurrency limit.</li>
 * </ol>
 *
 * @see CompositeBatchContext
 * @since 0.0.1
 */
public class CompositeBatchContextImpl implements CompositeBatchContext {
    @Getter
    private final ResponseTracker tracker;
    private final SubRequestCoordinator coordinator;
    private final Map<String, SubRequest> requestMap;
    private final CompositeRequestService requestService;
    private final String batchId;
    private final SubRequestContext context;
    private final int maxConcurrentSubRequests;
    private final boolean stopOnFailure;
    private final Queue<String> readyQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> started = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Disposable.Composite subscriptions = Disposables.composite();

    public CompositeBatchContextImpl(
            ResponseTracker tracker,
            SubRequestCoordinator coordinator,
            Map<String, SubRequest> requestMap,
            CompositeRequestService requestService,
            String batchId,
            SubRequestContext context,
            int maxConcurrentSubRequests
    ) {
        this(tracker, coordinator, requestMap, requestService, batchId, context, maxConcurrentSubRequests, false);
    }

    public CompositeBatchContextImpl(
            ResponseTracker tracker,
            SubRequestCoordinator coordinator,
            Map<String, SubRequest> requestMap,
            CompositeRequestService requestService,
            String batchId,
            SubRequestContext context,
            int maxConcurrentSubRequests,
            boolean stopOnFailure
    ) {
        if (maxConcurrentSubRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentSubRequests must be at least 1");
        }

        this.tracker = tracker;
        this.coordinator = coordinator;
        this.requestMap = requestMap;
        this.requestService = requestService;
        this.batchId = batchId;
        this.context = context;
        this.maxConcurrentSubRequests = maxConcurrentSubRequests;
        this.stopOnFailure = stopOnFailure;

        tracker.setOnSubRequestResolved(this::handleSubRequestResolved);
    }

    private void handleSubRequestResolved(String resolvedId) {
        if (subscriptions.isDisposed()) {
            return;
        }

        if (stopOnFailure && isFailure(tracker.getSubResponseMap().get(resolvedId))) {
            stopAfterFailure(resolvedId);
            return;
        }

        for (String id : coordinator.markResolved(resolvedId)) {
            if (coordinator.markInProgress(id)) {
                SubRequest subRequest = requestMap.get(id);
                if (hasFailedDependency(subRequest)) {
                    createFailedDependencyResponse(id, subRequest);
                } else {
                    readyQueue.add(id);
                }
            }
        }

        drainReadyQueue();
    }

    private static boolean isFailure(SubResponse response) {
        return response != null && (response.getHttpStatus() < 200 || response.getHttpStatus() >= 300);
    }

    /**
     * Cancels the rest of the batch and records why each remaining sub-request has no result.
     * A dispatch records {@link #started} before checking for disposal, so a call that may
     * have reached its handler is never reported as not executed.
     */
    private void stopAfterFailure(String failedId) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        subscriptions.dispose();
        readyQueue.clear();

        Map<String, SubResponse> responses = tracker.getSubResponseMap();
        for (String id : requestMap.keySet()) {
            if (!responses.containsKey(id)) {
                tracker.addResponse(id, started.contains(id)
                        ? stoppedResponse(id, HttpStatus.SERVICE_UNAVAILABLE,
                                "Cancelled because '" + failedId + "' failed. The handler may have already run.")
                        : stoppedResponse(id, HttpStatus.FAILED_DEPENDENCY,
                                "Not executed because '" + failedId + "' failed."));
            }
        }
    }

    private static SubResponse stoppedResponse(String id, HttpStatus status, String message) {
        return SubResponse.builder().referenceId(id).httpStatus(status.value()).body(message).build();
    }

    /**
     * Starts queued sub-requests while capacity remains. Safe to call from any thread: a
     * completing call releases its slot before draining, so queued work cannot be stranded.
     */
    private void drainReadyQueue() {
        while (!subscriptions.isDisposed() && !readyQueue.isEmpty()) {
            int running = inFlight.get();

            if (running >= maxConcurrentSubRequests) {
                return;
            }
            if (!inFlight.compareAndSet(running, running + 1)) {
                continue;
            }

            String id = readyQueue.poll();
            if (id == null) {
                inFlight.decrementAndGet();
                continue;
            }

            dispatch(id);
        }
    }

    private void dispatch(String id) {
        started.add(id);

        if (subscriptions.isDisposed()) {
            inFlight.decrementAndGet();
            return;
        }

        Mono<Void> call;

        try {
            call = requestService.forwardSubrequest(requestMap.get(id), batchId, context);
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            tracker.addResponse(id, SubResponse.builder()
                    .referenceId(id)
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
                    .body("Sub-request could not be dispatched")
                    .build());
            return;
        }

        subscriptions.add(call
                .doFinally(signal -> {
                    inFlight.decrementAndGet();
                    drainReadyQueue();
                })
                .subscribe());
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

    private void createFailedDependencyResponse(String subRequestId, SubRequest subRequest) {
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
    }

    public void startInitialRequests()  {
        for (String id : coordinator.getInitialReadySubRequests()) {
            if (coordinator.markInProgress(id)) {
                readyQueue.add(id);
            }
        }

        drainReadyQueue();
    }

    @Override
    public void cancel() {
        subscriptions.dispose();
    }
}
