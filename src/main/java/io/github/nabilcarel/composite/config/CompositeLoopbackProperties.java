package io.github.nabilcarel.composite.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
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
 * <p>When your application serves HTTPS, set {@code composite.loopback.protocol=https}.
 * Loopback calls then trust exactly the certificate configured in {@code server.ssl}, whether
 * self-signed or issued by an authority, and nothing else. No truststore changes are needed.
 * If the server's TLS is not configured through {@code server.ssl}, name a bundle holding its
 * certificate with {@code composite.loopback.ssl-bundle}.
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
     * Optional SSL bundle holding the certificate that HTTPS loopback calls trust.
     *
     * <p>When unset (the default), loopback calls trust exactly the certificate configured in
     * {@code server.ssl}. Set it only when the server's TLS is configured some other way. The
     * bundle's trust store, or else its key store, must contain the server's own certificate:
     * calls are pinned to it, so a certificate authority alone is not enough.
     */
    private String sslBundle;

    /** Deprecated and ignored. HTTPS loopback calls trust the server's own certificate. */
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
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

    /**
     * Returns whether the deprecated trust option is set. It has no effect.
     *
     * @return the configured value
     * @deprecated since 0.1.0: HTTPS loopback calls trust the server's own certificate, so the
     *     option is no longer needed. Remove it.
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    @DeprecatedConfigurationProperty(reason = "HTTPS loopback calls trust the server's own certificate")
    public boolean isTrustSelfSignedCertificates() {
        return trustSelfSignedCertificates;
    }

    /**
     * Sets the deprecated trust option. It has no effect.
     *
     * @param trustSelfSignedCertificates ignored
     * @deprecated since 0.1.0: HTTPS loopback calls trust the server's own certificate.
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public void setTrustSelfSignedCertificates(boolean trustSelfSignedCertificates) {
        this.trustSelfSignedCertificates = trustSelfSignedCertificates;
    }
}