package io.github.nabilcarel.composite.config;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Final allowlist check, made after Spring has selected the real handler for a sub-request.
 *
 * <p>{@link EndpointRegistry} rejects bad targets early, but only Spring's dispatcher knows
 * the final handler, including functional routes. A loopback call carries
 * {@link #DISPATCH_HEADER}, and this interceptor answers {@code 403} unless the selected
 * handler has {@link CompositeEndpoint &#64;CompositeEndpoint}.
 *
 * <p>The header grants nothing: a client that sends it directly can only restrict its own
 * request. Error dispatches are ignored so an annotated handler keeps its error status.
 *
 * @since 0.0.1
 */
public class CompositeDispatchInterceptor implements HandlerInterceptor {

    /** Header set on every loopback sub-request. Clients cannot set it inside a batch. */
    public static final String DISPATCH_HEADER = "X-Composite-Dispatch";

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || request.getHeader(DISPATCH_HEADER) == null) {
            return true;
        }

        if (handler instanceof HandlerMethod handlerMethod
                && handlerMethod.hasMethodAnnotation(CompositeEndpoint.class)) {
            return true;
        }

        response.setStatus(HttpStatus.FORBIDDEN.value());
        return false;
    }
}
