package io.github.nabilcarel.composite.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Reactor Netty {@link org.springframework.web.reactive.function.client.WebClient}
 * used to dispatch loopback sub-requests back to the same application instance.
 *
 * <p>Properties are bound to the {@code composite.loopback} prefix. The loopback
 * {@code WebClient} is created lazily on first use by
 * {@link io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration}.
 *
 * <h2>HTTPS configuration</h2>
 * <p>When your application runs behind TLS, set {@code composite.loopback.protocol=https}.
 * For production environments, import the server certificate into the JVM truststore.
 * For local development with self-signed certificates you may set
 * {@code composite.loopback.trust-self-signed-certificates=true},
 * but never enable that option in production.
 *
 * <h2>Example</h2>
 * <pre class="code">
 * composite.loopback.protocol=https
 * composite.loopback.connect-timeout=3s
 * composite.loopback.response-timeout=15s
 * </pre>
 *
 * @see CompositeProperties
 * @see io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "composite.loopback")
@Getter
@Setter
public final class CompositeLoopbackProperties {

    /**
     * TCP connection timeout for each loopback call.
     * Defaults to {@code 5s}.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Maximum time to wait for the loopback server to begin sending a response after the
     * request has been sent.
     * Defaults to {@code 10s}.
     */
    private Duration responseTimeout = Duration.ofSeconds(10);

    /**
     * Protocol used for loopback calls: {@code http} or {@code https}.
     *
     * <p>Must match the protocol on which your application is listening. Defaults to
     * {@code http}.
     */
    private String protocol = "http";

    /**
     * Whether to trust self-signed SSL certificates when {@link #protocol} is {@code https}.
     *
     * <p><strong>Warning:</strong> enabling this option bypasses certificate chain
     * validation and should only be used in development or testing environments. For
     * production, import your server certificate into the JVM truststore instead.
     * Defaults to {@code false}.
     */
    private boolean trustSelfSignedCertificates = false;
}