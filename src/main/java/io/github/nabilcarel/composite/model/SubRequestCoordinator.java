package io.github.nabilcarel.composite.model;

import java.util.List;

/**
 * Manages the dependency graph of sub-requests within a single composite execution and
 * determines the execution order.
 *
 * <p>The coordinator models each sub-request as a node in a directed acyclic graph (DAG).
 * Edges represent dependencies — if sub-request B contains {@code ${a.id}}, then B has an
 * edge to A and cannot start until A is resolved.
 *
 * <p>The execution protocol is:
 * <ol>
 *   <li>Retrieve the initial wave of dependency-free sub-requests via
 *       {@link #getInitialReadySubRequests()}.</li>
 *   <li>Atomically reserve each node with {@link #markInProgress(String)} before
 *       dispatching it to prevent double-dispatch in concurrent scenarios.</li>
 *   <li>After a sub-request completes, call {@link #markResolved(String)} to obtain the
 *       list of nodes whose remaining-dependency counter has just dropped to zero.</li>
 *   <li>Dispatch the newly-ready nodes (again guarded by {@link #markInProgress}).</li>
 * </ol>
 *
 * <p>All methods must be thread-safe.
 *
 * @see SubRequestCoordinatorImpl
 * @see io.github.nabilcarel.composite.service.CompositeBatchContext
 * @since 0.0.1
 */
public interface SubRequestCoordinator {

    /**
     * Returns the {@code referenceId}s of all sub-requests that have no dependencies and
     * can be dispatched immediately.
     *
     * <p>This method is called once, before any sub-requests are started. It is the entry
     * point of the execution pipeline.
     *
     * @return an unordered list of reference IDs ready for immediate dispatch; never
     *         {@code null}, but may be empty if every sub-request depends on another
     */
    List<String> getInitialReadySubRequests();

    /**
     * Marks the given sub-request as resolved and returns the {@code referenceId}s of
     * sub-requests that became ready as a result.
     *
     * <p>A dependent sub-request is added to the returned list only when all of its own
     * dependencies are resolved and its state is still {@code PENDING}.
     *
     * @param id the {@code referenceId} of the just-completed sub-request; must not be
     *           {@code null}
     * @return the list of {@code referenceId}s that are now ready to execute; never
     *         {@code null}, but may be empty
     */
    List<String> markResolved(String id);

    /**
     * Atomically transitions the given sub-request from {@code PENDING} to
     * {@code IN_PROGRESS}.
     *
     * <p>Returns {@code true} if the transition succeeded (i.e. the node was {@code PENDING}
     * at the moment of the call), or {@code false} if it was already in another state —
     * allowing concurrent callers to safely gate dispatch without double-submitting work.
     *
     * @param id the {@code referenceId} to transition; must not be {@code null}
     * @return {@code true} if the node was successfully marked as in-progress
     */
    boolean markInProgress(String id);

    /**
     * Returns whether the sub-request identified by {@code id} has been resolved.
     *
     * @param id the {@code referenceId} to check; must not be {@code null}
     * @return {@code true} if the node is in the {@code RESOLVED} state
     */
    boolean isResolved(String id);

    /**
     * Returns whether every sub-request in the batch has been resolved.
     *
     * @return {@code true} if all nodes are in the {@code RESOLVED} state
     */
    boolean isBatchResolved();
}
