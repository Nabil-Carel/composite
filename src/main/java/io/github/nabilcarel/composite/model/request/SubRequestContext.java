package io.github.nabilcarel.composite.model.request;

import io.github.nabilcarel.composite.model.response.CompositeDebugInfo;
import org.springframework.http.HttpHeaders;

/**
 * Per-batch data captured on the request thread before any sub-request is dispatched.
 *
 * <p>Dependent sub-requests run on client threads, potentially after the servlet thread has
 * returned, so they must never read from the original {@code HttpServletRequest}.
 *
 * @param forwardedHeaders the headers to send with every sub-request
 * @param debugInfo the debug collector, or {@code null} when debug mode is disabled
 * @since 0.0.1
 */
public record SubRequestContext(HttpHeaders forwardedHeaders, CompositeDebugInfo debugInfo) {
}
