package io.github.nabilcarel.composite.service;

/**
 * Coordinates the execution of a single composite request batch by wiring together the
 * {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker},
 * {@link io.github.nabilcarel.composite.model.SubRequestCoordinator SubRequestCoordinator},
 * and {@link CompositeRequestService}.
 *
 * <p>A {@code CompositeBatchContext} is created once per composite request by the
 * {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter filter} and
 * bridges the reactive sub-request completion events to the synchronous dependency graph:
 * each time a sub-request completes, the context consults the coordinator to find newly-ready
 * dependents and dispatches them.
 *
 * @see CompositeBatchContextImpl
 * @see io.github.nabilcarel.composite.config.filter.CompositeRequestFilter
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
     * {@link CompositeRequestService}.
     */
    void startInitialRequests();
}
