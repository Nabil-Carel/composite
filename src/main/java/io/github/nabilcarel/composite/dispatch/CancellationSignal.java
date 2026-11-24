package io.github.nabilcarel.composite.dispatch;

/**
 * Tells a {@link SubRequestDispatcher} that a result is no longer needed.
 *
 * @since 0.1.0
 */
public interface CancellationSignal {

    /**
     * Returns whether the sub-request has been cancelled.
     *
     * @return {@code true} once cancelled
     */
    boolean isCancelled();

    /**
     * Registers an action to run on cancellation. It runs immediately if already cancelled.
     *
     * @param action the action to run once
     */
    void onCancel(Runnable action);
}
