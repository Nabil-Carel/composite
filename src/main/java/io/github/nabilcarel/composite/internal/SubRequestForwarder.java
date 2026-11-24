package io.github.nabilcarel.composite.internal;

import io.github.nabilcarel.composite.model.request.SubRequest;
import reactor.core.publisher.Mono;

/** Internal scheduler callback. Not part of the public API. */
public interface SubRequestForwarder {

    /**
     * Resolves, validates and sends one sub-request, then records its result on the batch.
     *
     * @param subRequest the sub-request
     * @param batch the batch it belongs to
     * @return a publisher that completes after the result is recorded
     */
    Mono<Void> forwardSubrequest(SubRequest subRequest, BatchExecution batch);
}
