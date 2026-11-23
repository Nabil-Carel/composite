package io.github.nabilcarel.composite.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

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
 * composite.loopback.max-response-size=1MB
 * </pre>
 *
 * @see CompositeProperties
 * @see io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "composite.loopback")
@Validated
@Getter
@Setter
public final class CompositeLoopbackProperties {

    /**
     * TCP connection timeout for each loopback call.
     * Defaults to {@code 5s}.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Optional maximum time to wait for the loopback server to begin sending a response.
     *
     * <p>When unset (the default), each call is limited by {@code composite.sub-request-timeout}
     * or, if that is also unset, {@code composite.request-timeout}.
     */
    private Duration responseTimeout;

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

    /**
     * Maximum size of one sub-response held in memory.
     *
     * <p>A larger sub-response fails with {@code 503} and blocks its dependents. Each batch
     * can hold up to one such response per sub-request, so raising this value raises the
     * memory a single composite request can use. Must be between {@code 1B} and
     * {@code 2147483647B}. Defaults to {@code 256KB}.
     */
    private DataSize maxResponseSize = DataSize.ofKilobytes(256);

    /**
     * Maximum number of loopback connections across all batches.
     *
     * <p>Calls above this limit wait for a free connection until the batch deadline, which
     * prevents bursts from overflowing the server's accept queue or occupying every request
     * thread. Keep it below {@code server.tomcat.threads.max}. Defaults to {@code 100}.
     */
    @Min(1)
    private int maxConnections = 100;
}