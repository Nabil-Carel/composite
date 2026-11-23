package io.github.nabilcarel.composite.util;

import io.github.nabilcarel.composite.config.CompositeProperties;
import java.util.Locale;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Decides which headers a client may set on an individual sub-request.
 *
 * <p>A header must be on {@code composite.security.client-allowed-headers}. Credentials,
 * proxy, identity, hop-by-hop and composite tracking headers are always refused, even if
 * they are listed, because an edge proxy cannot inspect or strip headers inside a batch.
 * Credentials reach sub-requests only through {@code composite.security.forwarded-headers}.
 *
 * @since 0.0.1
 */
@UtilityClass
public class SubRequestHeaderPolicy {

    private static final Set<String> ALWAYS_BLOCKED = Set.of(
            "authorization", "cookie", "proxy-authorization", "proxy-authenticate",
            "connection", "keep-alive", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length",
            "forwarded", "via", "x-real-ip", "x-client-ip", "true-client-ip",
            "cf-connecting-ip", "x-cluster-client-ip", "x-original-url", "x-rewrite-url",
            "x-http-method-override", "x-http-method", "x-method-override");

    /**
     * Returns whether a client may set the given header on a sub-request.
     *
     * @param headerName the header name, in any case
     * @param properties the composite configuration
     * @return {@code true} if the header is allowed
     */
    public static boolean isClientHeaderAllowed(String headerName, CompositeProperties properties) {
        if (headerName == null) {
            return false;
        }

        String name = normalize(headerName);
        CompositeProperties.HeaderInjection tracking = properties.getHeaderInjection();

        if (ALWAYS_BLOCKED.contains(name)
                || name.startsWith("x-forwarded-")
                || name.startsWith("x-composite-")
                || name.equals(normalize(tracking.getRequestHeader()))
                || name.equals(normalize(tracking.getRequestIdHeader()))
                || name.equals(normalize(tracking.getSubRequestIdHeader()))
                || properties.getSecurity().getBlockedHeaders().stream()
                        .anyMatch(blocked -> name.equals(normalize(blocked)))) {
            return false;
        }

        return properties.getSecurity().getClientAllowedHeaders().stream()
                .anyMatch(allowed -> name.equals(normalize(allowed)));
    }

    private static String normalize(String headerName) {
        return headerName.trim().toLowerCase(Locale.ROOT);
    }
}
