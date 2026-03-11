package io.github.nabilcarel.composite.model;

import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Accumulates sub-responses for a single composite request execution and signals
 * completion when all expected responses have been collected.
 *
 * <p>A {@code ResponseTracker} is created once per composite request (keyed by a UUID
 * {@code requestId}) and stored in the shared
 * {@link java.util.concurrent.ConcurrentMap ConcurrentMap&lt;String, ResponseTracker&gt;}
 * bean. It serves two purposes:
 * <ol>
 *   <li><strong>Accumulation</strong> — each sub-response is deposited via
 *       {@link #addResponse(String, SubResponse)}. The tracker counts down from the total
 *       number of expected sub-responses.</li>
 *   <li><strong>Completion signalling</strong> — when the count reaches zero, the tracker
 *       completes a {@link CompletableFuture}&lt;{@link CompositeResponse}&gt; that the
 *       controller thread is waiting on.</li>
 * </ol>
 *
 * <p>Implementations must be thread-safe: multiple reactor threads may call
 * {@link #addResponse} concurrently as parallel sub-requests complete.
 *
 * @see ResponseTrackerImpl
 * @see io.github.nabilcarel.composite.service.CompositeBatchContext
 * @since 0.0.1
 */
public interface ResponseTracker {

    /**
     * Returns the live map of sub-responses collected so far, keyed by {@code referenceId}.
     *
     * <p>The returned map is updated concurrently as sub-requests complete. Callers that
     * need a consistent snapshot should take a copy rather than iterating the live map.
     *
     * @return the concurrent sub-response map; never {@code null}
     */
    Map<String, SubResponse> getSubResponseMap();

    /**
     * Returns the {@link CompletableFuture} that will be resolved with the aggregated
     * {@link CompositeResponse} once all sub-requests have completed.
     *
     * <p>The future is completed normally when {@link #addResponse} has been called for
     * every expected sub-request, and completed exceptionally via {@link #cancel(Throwable)}
     * on unrecoverable errors.
     *
     * @return the completion future; never {@code null}
     */
    CompletableFuture<CompositeResponse> getFuture();

    /**
     * Records the outcome of a single sub-request and decrements the pending-response
     * counter.
     *
     * <p>When the counter reaches zero this method completes {@link #getFuture()} and
     * invokes the registered {@link #setOnSubRequestResolved(Consumer) callback}. This
     * method must be safe to call from multiple threads simultaneously.
     *
     * @param subRequestId the {@code referenceId} of the completed sub-request; must not
     *                     be {@code null}
     * @param subResponse  the sub-response to record; must not be {@code null}
     */
    void addResponse(String subRequestId, SubResponse subResponse);

    /**
     * Registers a callback to be invoked each time a sub-request completes.
     *
     * <p>The callback receives the {@code referenceId} of the just-completed sub-request and
     * is responsible for consulting the
     * {@link io.github.nabilcarel.composite.model.SubRequestCoordinator SubRequestCoordinator}
     * to determine which dependent sub-requests are now ready to execute.
     *
     * @param callback the consumer to invoke on each sub-request resolution; must not be
     *                 {@code null}
     */
    void setOnSubRequestResolved(Consumer<String> callback);

    /**
     * Cancels this tracker by completing {@link #getFuture()} exceptionally with the
     * given cause.
     *
     * @param t the cause of cancellation; must not be {@code null}
     */
    void cancel(Throwable t);
}
