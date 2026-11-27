package io.github.nabilcarel.composite.internal;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts composite requests currently running, to limit them and for health reporting. */
public final class ActiveBatches {

    private final AtomicInteger count = new AtomicInteger();

    /** Records that a batch started. */
    public void increment() {
        count.incrementAndGet();
    }

    /**
     * Records that a batch started, unless the limit is already reached.
     *
     * @param limit the most batches allowed to run at once
     * @return {@code true} if the batch may start, in which case {@link #decrement()} must follow
     */
    public boolean tryIncrement(int limit) {
        while (true) {
            int current = count.get();
            if (current >= limit) {
                return false;
            }
            if (count.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Records that a batch finished. */
    public void decrement() {
        count.decrementAndGet();
    }

    /**
     * Returns the number of running batches.
     *
     * @return the count
     */
    public int count() {
        return count.get();
    }

    /**
     * Returns whether no batch is running.
     *
     * @return {@code true} if none are running
     */
    public boolean isEmpty() {
        return count.get() == 0;
    }
}
