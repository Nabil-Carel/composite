package io.github.nabilcarel.composite.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the HTTP client that sends loopback sub-requests back to the
 * same application instance.
 *
 * <p>Properties are bound to the {@code composite.loopback} prefix. The client is created
 * lazily on first use by
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

    /** HTTP clients available for loopback calls. */
    public enum Client {
        /** The non-blocking JDK {@code java.net.http.HttpClient}. Needs no extra dependency. */
        JDK,
        /** Reactor Netty's {@code WebClient}. Requires {@code spring-boot-starter-webflux}. */
        WEBCLIENT
    }

    /**
     * HTTP client used for loopback calls.
     *
     * <p>Both clients are non-blocking. Defaults to {@code jdk}, which needs no extra
     * dependency. A {@link io.github.nabilcarel.composite.dispatch.SubRequestDispatcher} bean
     * replaces either one.
     */
    private Client client = Client.JDK;

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
     * Whether loopback calls accept any certificate when {@link #protocol} is {@code https}.
     *
     * <p><strong>Insecure, for development and tests only.</strong> Disabled by default.
     * When enabled, loopback calls skip certificate and host name checks entirely, so
     * anything able to intercept local traffic could read or change them, including the
     * forwarded credentials. A warning is logged at startup while it is on. In production,
     * add your server certificate to the JVM truststore instead.
     */
    // Off by default. Enabling it is logged as a warning at startup.
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
     * Maximum number of loopback connections and running sub-request handlers across all
     * batches.
     *
     * <p>Calls above the connection limit wait until the batch deadline. If handlers from
     * cancelled calls are still running at the limit, new sub-requests fail with {@code 503}.
     * When unset, it defaults to half of the server resource that runs out first: half of
     * {@code server.tomcat.threads.max} ({@code 100} by default) on platform threads, or half
     * of {@code server.tomcat.max-connections} ({@code 4096} by default) when
     * {@code spring.threads.virtual.enabled=true}.
     */
    @Min(1)
    private Integer maxConnections;
}