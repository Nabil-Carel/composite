package io.github.nabilcarel.composite.service;

/**
 * Coordinates the execution of a single composite request batch by wiring together the
 * {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker},
 * {@link io.github.nabilcarel.composite.model.SubRequestCoordinator SubRequestCoordinator},
 * and the internal sub-request forwarder.
 *
 * <p>A {@code CompositeBatchContext} is created once per composite request by
 * {@link CompositeRequestService#execute} and bridges the reactive sub-request completion
 * events to the synchronous dependency graph: each time a sub-request completes, the context
 * consults the coordinator to find newly-ready dependents and dispatches them.
 *
 * @see CompositeBatchContextImpl
 * @since 0.0.1
 */
public interface CompositeBatchContext {

    /**
     * Fires the initial wave of dependency-free sub-requests.
     *
     * <p>This method must be called exactly once after the context is created. It retrieves
     * the sub-requests that have no dependencies from the
     * {@link io.github.nabilcarel.composite.model.SubRequestCoordinator SubRequestCoordinator},
     * marks each as {@code IN_PROGRESS}, and dispatches them concurrently via the
     * internal sub-request forwarder.
     */
    void startInitialRequests();

    /**
     * Cancels in-flight loopback calls and prevents further sub-requests from starting.
     *
     * <p>This method is idempotent. It cannot stop a server handler that is already running.
     */
    void cancel();
}
