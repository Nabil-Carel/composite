package io.github.nabilcarel.composite.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * The application's own base URL, known once the embedded server has chosen its port.
 *
 * <p>Events from a separate management server are ignored, so sub-requests always reach the
 * application port.
 */
public final class LoopbackAddress implements ApplicationListener<ServletWebServerInitializedEvent> {

    private final String protocol;
    private final Environment environment;
    private volatile int port;
    private volatile String baseUrl;

    public LoopbackAddress(String protocol, Environment environment) {
        this.protocol = protocol;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() == null) {
            port = event.getWebServer().getPort();
            baseUrl = null;
        }
    }

    /**
     * Returns the base URL, including the {@code server.address} the server is bound to, the
     * context path and the Spring MVC servlet path.
     *
     * @return the base URL without a trailing slash
     * @throws IllegalStateException if the server has not started
     */
    public String baseUrl() {
        String current = baseUrl;
        if (current == null) {
            if (port <= 0) {
                throw new IllegalStateException("Composite loopback used before the web server started");
            }
            current = String.format("%s://%s:%d%s%s", protocol, host(environment.getProperty("server.address")), port,
                    normalizePathPrefix(environment.getProperty("server.servlet.context-path")),
                    normalizePathPrefix(environment.getProperty("spring.mvc.servlet.path")));
            baseUrl = current;
        }
        return current;
    }

    /**
     * Returns the host the server listens on. A server bound to one address refuses
     * connections to {@code localhost}, so that address is used. Without one, or with a
     * wildcard address, the server listens on every interface and {@code localhost} works.
     */
    static String host(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) {
            return "localhost";
        }

        String address = serverAddress.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        try {
            if (InetAddress.getByName(address).isAnyLocalAddress()) {
                return "localhost";
            }
        } catch (UnknownHostException e) {
            // The server could not bind to it either, so let the call report the failure.
        }
        return address.indexOf(':') >= 0 ? "[" + address + "]" : address;
    }

    private static String normalizePathPrefix(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        String normalized = path.trim();
        if (normalized.endsWith("/*")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
