package io.github.nabilcarel.composite.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * Propagates authentication and other security-sensitive headers from the incoming composite
 * request to each outbound sub-request.
 *
 * <p>The set of headers to forward is governed by
 * {@code composite.security.forwarded-headers}. By default no headers are forwarded; they must be
 * explicitly enumerated. Common candidates include {@code Authorization}, {@code Cookie},
 * and {@code X-API-Key}.
 *
 * <h2>Configuration example</h2>
 * <pre class="code">
 * composite.security.forwarded-headers=Authorization,Cookie
 * </pre>
 *
 * @see AuthenticationForwardingServiceImpl
 * @see io.github.nabilcarel.composite.config.CompositeProperties.Security
 * @since 0.0.1
 */
public interface AuthenticationForwardingService {

    /**
     * Copies the configured forwarded headers from {@code originalRequest} into
     * {@code targetHeaders}, skipping any header whose value is absent from the original
     * request.
     *
     * @param originalRequest the incoming composite HTTP request; must not be {@code null}
     * @param targetHeaders   the mutable {@link HttpHeaders} of the outbound sub-request to
     *                        populate; must not be {@code null}
     */
    void forwardAuthentication(HttpServletRequest originalRequest, HttpHeaders targetHeaders);
}
