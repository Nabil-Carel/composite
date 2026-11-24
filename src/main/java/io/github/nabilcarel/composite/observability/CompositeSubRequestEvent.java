package io.github.nabilcarel.composite.observability;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Java Flight Recorder event covering one loopback sub-request.
 *
 * <p>The endpoint is the mapped route pattern, such as {@code /users/{id}}. Resolved URLs,
 * headers and bodies are never recorded because they can contain sensitive values.
 *
 * @since 0.1.0
 */
@Name("io.github.nabilcarel.composite.SubRequest")
@Label("Composite Sub-request")
@Category("Composite")
@Description("One loopback sub-request")
@StackTrace(false)
public final class CompositeSubRequestEvent extends Event {

    @Label("Batch ID")
    String batchId;

    @Label("Reference ID")
    String referenceId;

    @Label("HTTP Method")
    String method;

    @Label("Endpoint Pattern")
    String endpoint;

    @Label("HTTP Status")
    int status;

    @Label("Outcome")
    @Description("COMPLETED, FAILED or CANCELLED")
    String outcome;

    /**
     * Ends the event and commits it if a recording wants it.
     *
     * @param batchId the generated batch ID
     * @param referenceId the validated sub-request reference ID
     * @param method the HTTP method
     * @param endpoint the mapped route pattern
     * @param status the recorded HTTP status, or {@code 0} if none was recorded
     * @param cancelled whether the call was cancelled
     */
    public void finish(String batchId, String referenceId, String method, String endpoint, int status,
                       boolean cancelled) {
        end();
        if (shouldCommit()) {
            this.batchId = batchId;
            this.referenceId = referenceId;
            this.method = method;
            this.endpoint = endpoint;
            this.status = status;
            this.outcome = cancelled ? "CANCELLED" : status >= 200 && status < 300 ? "COMPLETED" : "FAILED";
            commit();
        }
    }
}
