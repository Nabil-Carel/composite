package io.github.nabilcarel.composite.internal;

import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import reactor.core.publisher.Mono;

/** Internal scheduler callback. Not part of the public API. */
public interface SubRequestForwarder {

    /**
     * Resolves, validates and sends one sub-request, then records its result.
     *
     * @param subRequest the sub-request
     * @param requestId the batch ID
     * @param context data captured for the batch
     * @return a publisher that completes after the result is recorded
     */
    Mono<Void> forwardSubrequest(SubRequest subRequest, String requestId, SubRequestContext context);
}
