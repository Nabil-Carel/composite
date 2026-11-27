package io.github.nabilcarel.composite.internal;

import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

/**
 * The state and scheduling of one composite request.
 *
 * <p>One lock guards the dependency graph, the ready queue and the concurrency count. Sub-requests
 * are started and futures are completed outside the lock, so application code never runs while
 * it is held. The first result recorded for a sub-request wins.
 */
public final class BatchExecution {

    private static final class Node {
        private final SubRequest request;
        private final List<Node> dependents = new ArrayList<>();
        private int remainingDependencies;
        private boolean claimed;
        private boolean started;

        private Node(SubRequest request) {
            this.request = request;
        }

        private String id() {
            return request.getReferenceId();
        }
    }

    private final String id;
    private final SubRequestForwarder forwarder;
    private final SubRequestContext context;
    private final int maxConcurrentSubRequests;
    private final boolean stopOnFailure;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, SubResponse> responses = new ConcurrentHashMap<>();
    private final Map<String, SubResponse> responsesView = Collections.unmodifiableMap(responses);
    private final CompletableFuture<CompositeResponse> future = new CompletableFuture<>();
    private final Disposable.Composite subscriptions = Disposables.composite();
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Node> ready = new ArrayDeque<>();
    private int inFlight;
    private boolean closed;

    /**
     * Creates a batch.
     *
     * @param id the batch ID
     * @param requests sub-requests by reference ID
     * @param forwarder sends each sub-request
     * @param context data captured for the batch
     * @param maxConcurrentSubRequests maximum sub-requests running at once
     * @param stopOnFailure whether the first failure stops the remaining work
     * @throws IllegalArgumentException if the limit is below one or a dependency is unknown
     */
    public BatchExecution(String id, Map<String, SubRequest> requests, SubRequestForwarder forwarder,
                          SubRequestContext context, int maxConcurrentSubRequests, boolean stopOnFailure) {
        if (maxConcurrentSubRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentSubRequests must be at least 1");
        }

        this.id = id;
        this.forwarder = forwarder;
        this.context = context;
        this.maxConcurrentSubRequests = maxConcurrentSubRequests;
        this.stopOnFailure = stopOnFailure;

        requests.forEach((referenceId, request) -> nodes.put(referenceId, new Node(request)));
        for (Node node : nodes.values()) {
            Set<String> dependencies = node.request.getDependencies();
            if (dependencies == null) {
                continue;
            }
            node.remainingDependencies = dependencies.size();
            for (String dependency : dependencies) {
                Node parent = nodes.get(dependency);
                if (parent == null) {
                    throw new IllegalArgumentException("Unknown dependency: " + dependency);
                }
                parent.dependents.add(node);
            }
        }
    }

    /**
     * Returns the batch ID.
     *
     * @return the ID
     */
    public String id() {
        return id;
    }

    /**
     * Returns the data captured for this batch.
     *
     * @return the context
     */
    public SubRequestContext context() {
        return context;
    }

    /**
     * Returns a read-only live view of the recorded responses.
     *
     * @return responses by reference ID
     */
    public Map<String, SubResponse> responses() {
        return responsesView;
    }

    /**
     * Returns the future completed when every sub-request has a result.
     *
     * @return the batch future
     */
    public CompletableFuture<CompositeResponse> future() {
        return future;
    }

    /** Starts every sub-request that has no dependencies. */
    public void start() {
        lock.lock();
        try {
            if (!closed) {
                for (Node node : nodes.values()) {
                    if (node.remainingDependencies == 0 && !node.claimed) {
                        node.claimed = true;
                        if (!responses.containsKey(node.id())) {
                            ready.add(node);
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        drain();
    }

    /**
     * Records a result and starts any work it unlocks. Later results for the same ID, and every
     * result after {@link #cancel()} or a stop on failure, are ignored.
     *
     * @param referenceId the sub-request reference ID
     * @param response the result
     */
    public void addResponse(String referenceId, SubResponse response) {
        Objects.requireNonNull(response, "response");
        boolean stopped = false;
        boolean complete;

        lock.lock();
        try {
            // Closing is terminal. A stop on failure has already recorded every missing result,
            // and after cancel() nobody reads further results.
            Node node = nodes.get(referenceId);
            if (closed || node == null || responses.putIfAbsent(referenceId, response) != null) {
                return;
            }

            if (stopOnFailure && isFailure(response)) {
                stopAfterFailure(referenceId);
                stopped = true;
            } else {
                unlockDependents(node);
            }
            complete = responses.size() == nodes.size();
        } finally {
            lock.unlock();
        }

        if (stopped) {
            subscriptions.dispose();
        }
        if (complete) {
            complete();
        }
        drain();
    }

    /** Cancels running calls and prevents more sub-requests from starting. Idempotent. */
    public void cancel() {
        lock.lock();
        try {
            closed = true;
            ready.clear();
        } finally {
            lock.unlock();
        }
        subscriptions.dispose();
    }

    /**
     * Completes the batch with a failure.
     *
     * @param failure the failure
     */
    public void fail(Throwable failure) {
        future.completeExceptionally(failure);
    }

    private void unlockDependents(Node resolved) {
        Deque<Node> pending = new ArrayDeque<>();
        pending.add(resolved);

        while (!pending.isEmpty()) {
            for (Node dependent : pending.poll().dependents) {
                if (--dependent.remainingDependencies != 0 || dependent.claimed) {
                    continue;
                }

                dependent.claimed = true;
                if (responses.containsKey(dependent.id())) {
                    continue;
                }

                Set<String> failed = failedDependencies(dependent);
                if (failed.isEmpty()) {
                    ready.add(dependent);
                } else {
                    responses.put(dependent.id(), response(dependent.id(), HttpStatus.FAILED_DEPENDENCY,
                            "Failed Dependency: not executed because these sub-requests failed: "
                                    + String.join(", ", failed)));
                    pending.add(dependent);
                }
            }
        }
    }

    /**
     * Any non-2xx dependency counts, as it does for {@code hasErrors} and stop on failure. A 1xx
     * or 3xx response has no body to reference, so its dependents could only fail later.
     */
    private Set<String> failedDependencies(Node node) {
        Set<String> failed = new TreeSet<>();
        for (String dependency : node.request.getDependencies()) {
            SubResponse response = responses.get(dependency);
            if (response != null && isFailure(response)) {
                failed.add(dependency);
            }
        }
        return failed;
    }

    /** A claimed call may have reached its handler, so it is never reported as not executed. */
    private void stopAfterFailure(String failedId) {
        closed = true;
        ready.clear();

        for (Node node : nodes.values()) {
            if (!responses.containsKey(node.id())) {
                responses.put(node.id(), node.started
                        ? response(node.id(), HttpStatus.SERVICE_UNAVAILABLE,
                                "Cancelled because '" + failedId + "' failed. The handler may have already run.")
                        : response(node.id(), HttpStatus.FAILED_DEPENDENCY,
                                "Not executed because '" + failedId + "' failed."));
            }
        }
    }

    private void drain() {
        while (true) {
            Node next;

            lock.lock();
            try {
                if (closed || inFlight >= maxConcurrentSubRequests) {
                    return;
                }
                do {
                    next = ready.poll();
                } while (next != null && responses.containsKey(next.id()));
                if (next == null) {
                    return;
                }
                next.started = true;
                inFlight++;
            } finally {
                lock.unlock();
            }

            dispatch(next);
        }
    }

    private void dispatch(Node node) {
        if (subscriptions.isDisposed()) {
            release();
            return;
        }

        Mono<Void> call;
        try {
            call = forwarder.forwardSubrequest(node.request, this);
        } catch (RuntimeException e) {
            release();
            addResponse(node.id(), response(node.id(), HttpStatus.INTERNAL_SERVER_ERROR,
                    "Sub-request could not be dispatched. Check the server logs."));
            return;
        }

        subscriptions.add(call
                .doFinally(signal -> {
                    release();
                    drain();
                })
                .subscribe());
    }

    private void release() {
        lock.lock();
        try {
            inFlight--;
        } finally {
            lock.unlock();
        }
    }

    private void complete() {
        boolean hasErrors = responses.values().stream().anyMatch(BatchExecution::isFailure);
        future.complete(CompositeResponse.builder().responses(responses).hasErrors(hasErrors).build());
    }

    private static boolean isFailure(SubResponse response) {
        return response.getHttpStatus() < 200 || response.getHttpStatus() >= 300;
    }

    private static SubResponse response(String referenceId, HttpStatus status, String message) {
        return SubResponse.builder().referenceId(referenceId).httpStatus(status.value()).body(message).build();
    }
}
