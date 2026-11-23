package io.github.nabilcarel.composite.config;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * Final allowlist check and server-side concurrency limit for loopback sub-requests.
 *
 * <p>{@link EndpointRegistry} rejects bad targets early, but only Spring's dispatcher knows
 * the final handler, including functional routes. A loopback call carries
 * {@link #DISPATCH_HEADER}, and this interceptor answers {@code 403} unless the selected
 * handler has {@link CompositeEndpoint &#64;CompositeEndpoint}.
 *
 * <p>A cancelled client call frees its connection while its handler keeps running, so a
 * connection limit alone cannot bound server threads. This interceptor therefore counts
 * running sub-request handlers and answers {@code 503} above the limit.
 *
 * <p>The header grants nothing: a client that sends it directly can only restrict its own
 * request. Error dispatches are ignored so an annotated handler keeps its error status.
 *
 * @since 0.0.1
 */
public class CompositeDispatchInterceptor implements AsyncHandlerInterceptor {

    /** Header set on every loopback sub-request. Clients cannot set it inside a batch. */
    public static final String DISPATCH_HEADER = "X-Composite-Dispatch";

    private static final String PERMIT_ATTRIBUTE = CompositeDispatchInterceptor.class.getName() + ".permit";

    private final Semaphore permits;

    /**
     * Creates the interceptor.
     *
     * @param maxConcurrentHandlers maximum sub-request handlers running at the same time
     */
    public CompositeDispatchInterceptor(int maxConcurrentHandlers) {
        this.permits = new Semaphore(maxConcurrentHandlers);
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || request.getHeader(DISPATCH_HEADER) == null) {
            return true;
        }

        if (!(handler instanceof HandlerMethod handlerMethod)
                || !handlerMethod.hasMethodAnnotation(CompositeEndpoint.class)) {
            reject(response, HttpStatus.FORBIDDEN,
                    "This route is not available for composite execution.");
            return false;
        }

        if (!permits.tryAcquire()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many sub-requests are running. Retry later.");
            return false;
        }

        request.setAttribute(PERMIT_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        release(request);
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                @Nullable Exception ex) {
        release(request);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message) {
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(message);
        } catch (IOException e) {
            // The status is already set, and the client may have disconnected.
        }
    }

    private void release(HttpServletRequest request) {
        if (request.getAttribute(PERMIT_ATTRIBUTE) != null) {
            request.removeAttribute(PERMIT_ATTRIBUTE);
            permits.release();
        }
    }
}
