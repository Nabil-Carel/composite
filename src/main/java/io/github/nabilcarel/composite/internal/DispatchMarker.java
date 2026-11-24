package io.github.nabilcarel.composite.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random per-application value that identifies genuine loopback sub-requests.
 *
 * <p>A request with any other value is treated as a normal direct request, so outsiders cannot
 * consume the sub-request handler limit by sending the header themselves.
 */
public final class DispatchMarker {

    private final String value;

    public DispatchMarker() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        value = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /**
     * Returns the value to send on loopback calls.
     *
     * @return the marker value
     */
    public String value() {
        return value;
    }

    /**
     * Compares a received header in constant time.
     *
     * @param candidate the received header value
     * @return {@code true} if it is this application's marker
     */
    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(
                value.getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
