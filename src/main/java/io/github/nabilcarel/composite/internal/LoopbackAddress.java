package io.github.nabilcarel.composite.internal;

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
     * Returns the base URL, including the context path and Spring MVC servlet path.
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
            current = String.format("%s://localhost:%d%s%s", protocol, port,
                    normalizePathPrefix(environment.getProperty("server.servlet.context-path")),
                    normalizePathPrefix(environment.getProperty("spring.mvc.servlet.path")));
            baseUrl = current;
        }
        return current;
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
