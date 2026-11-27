package io.github.nabilcarel.composite.internal;

import io.github.nabilcarel.composite.dispatch.CancellationSignal;
import java.util.ArrayList;
import java.util.List;

/** Thread-safe cancellation signal. Each registered action runs exactly once. */
public final class DefaultCancellationSignal implements CancellationSignal {

    private final List<Runnable> actions = new ArrayList<>();
    private boolean cancelled;

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void onCancel(Runnable action) {
        synchronized (this) {
            if (!cancelled) {
                actions.add(action);
                return;
            }
        }
        action.run();
    }

    /** Cancels the signal and runs registered actions outside the lock. */
    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toRun = List.copyOf(actions);
            actions.clear();
        }
        toRun.forEach(Runnable::run);
    }
}
