package io.github.nabilcarel.composite.dispatch;

import java.util.concurrent.CompletionStage;

/**
 * Sends one fully prepared sub-request back to the current application.
 *
 * <p>Validation, reference resolution, header policy, timeouts and response parsing happen
 * outside the dispatcher. Declare a bean of this type to replace the default HTTP client.
 *
 * <p>Complete the returned stage exceptionally for transport failures. An HTTP error response
 * is a normal {@link DispatchResult}. Implementations must not follow redirects and must send
 * the request only to this application.
 *
 * <p>When the {@link CancellationSignal} fires, abort the call and release any connection or
 * permit it holds. Composite ignores results that arrive after cancellation, so a call that
 * keeps running only wastes resources.
 *
 * @since 0.1.0
 */
public interface SubRequestDispatcher {

    /**
     * Dispatches a sub-request.
     *
     * @param request the prepared request
     * @param cancellation signalled when the result is no longer needed
     * @return a stage completed with the HTTP result
     */
    CompletionStage<DispatchResult> dispatch(PreparedSubRequest request, CancellationSignal cancellation);
}
