package io.github.nabilcarel.composite.observability;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Java Flight Recorder event covering one accepted composite batch.
 *
 * <p>The event contains no URLs, headers or bodies. It costs almost nothing unless a recording
 * has enabled it.
 *
 * @since 0.1.0
 */
@Name("io.github.nabilcarel.composite.Batch")
@Label("Composite Batch")
@Category("Composite")
@Description("One accepted composite batch")
@StackTrace(false)
public final class CompositeBatchEvent extends Event {

    @Label("Batch ID")
    String batchId;

    @Label("Sub-requests")
    int subRequests;

    @Label("Failed Sub-requests")
    int failedSubRequests;

    @Label("Stop On Failure")
    boolean stopOnFailure;

    @Label("Outcome")
    @Description("SUCCESS, PARTIAL, TIMEOUT, CANCELLED or ERROR")
    String outcome;

    /**
     * Ends the event and commits it if a recording wants it.
     *
     * @param batchId the generated batch ID
     * @param subRequests the number of sub-requests in the batch
     * @param failedSubRequests the number of recorded non-2xx sub-responses
     * @param stopOnFailure whether the batch used {@code stopOnFailure}
     * @param outcome the batch outcome
     */
    public void finish(String batchId, int subRequests, int failedSubRequests, boolean stopOnFailure,
                       String outcome) {
        end();
        if (shouldCommit()) {
            this.batchId = batchId;
            this.subRequests = subRequests;
            this.failedSubRequests = failedSubRequests;
            this.stopOnFailure = stopOnFailure;
            this.outcome = outcome;
            commit();
        }
    }
}
