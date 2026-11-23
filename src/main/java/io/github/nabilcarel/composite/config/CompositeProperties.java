package io.github.nabilcarel.composite.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the Composite library, bound to the {@code composite} prefix.
 *
 * <p>All properties are optional; sensible defaults are provided for every field. To customise
 * the library, add the relevant {@code composite.*} keys to your application's
 * {@code application.properties} or {@code application.yml}.
 *
 * <h2>Minimal configuration</h2>
 * <pre class="code">
 * # application.properties
 * composite.base-path=/api/batch
 * composite.request-timeout=30s
 * composite.security.forwarded-headers=Authorization,Cookie
 * </pre>
 *
 * @see CompositeLoopbackProperties
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "composite")
@Validated
@Getter
@Setter
public class CompositeProperties {

    /**
     * Configuration for the built-in
     * {@link io.github.nabilcarel.composite.controller.CompositeController}.
     *
     * @see Controller
     */
    private Controller controller = new Controller();

    /**
     * Base path under which the composite endpoints are mounted.
     *
     * <p>The execution endpoint will be available at
     * {@code {basePath}/execute} and the endpoint discovery endpoint at
     * {@code {basePath}/endpoints}. Defaults to {@code /api/composite}.
     */
    private String basePath = "/api/composite";

    /**
     * Maximum wall-clock time allowed for the entire composite request to complete.
     *
     * <p>When sub-requests have sequential dependencies (B depends on A), the effective
     * timeout must account for the cumulative execution time of the entire chain.
     * Defaults to {@code 60s}.
     */
    private Duration requestTimeout = Duration.ofSeconds(60);

    /**
     * Maximum number of sub-requests permitted in a single composite request.
     *
     * <p>Requests that exceed this limit are rejected during validation. Defaults to
     * {@code 25}.
     */
    private int maxSubRequestsPerComposite = 25;

    /**
     * Maximum number of sub-requests from one batch that may run at the same time.
     *
     * <p>Each running sub-request occupies a server request thread, so this limit prevents
     * one batch from starving normal traffic. Must be at least {@code 1}. Defaults to
     * {@code 5}.
     */
    @Min(1)
    private int maxConcurrentSubRequests = 5;

    /**
     * Configuration for composite-request tracking headers injected into each sub-request.
     *
     * @see HeaderInjection
     */
    private HeaderInjection headerInjection = new HeaderInjection();

    /**
     * Maximum allowed depth of the sub-request dependency graph.
     *
     * <p>A depth of {@code n} means that a chain of {@code n} sequentially-dependent
     * sub-requests is permitted. Requests that exceed this limit are rejected during
     * validation. Defaults to {@code 10}.
     */
    private int maxDepth = 10;

    /**
     * Per-sub-request timeout.
     *
     * <p>When set, each individual loopback call is constrained to this duration. When
     * {@code null} (the default), the overall {@link #requestTimeout} is used as the
     * per-sub-request limit.
     */
    private Duration subRequestTimeout;

    /**
     * Whether to include the dependency graph and resolved request details in the
     * {@link io.github.nabilcarel.composite.model.response.CompositeResponse}.
     *
     * <p><strong>Warning:</strong> enabling debug mode in production may expose sensitive
     * internal details such as resolved URLs and request bodies. Defaults to {@code false}.
     */
    private boolean debugEnabled = false;

    /** Security-related configuration. */
    private Security security = new Security();

    // -------------------------------------------------------------------------
    // Nested configuration classes
    // -------------------------------------------------------------------------

    /**
     * Configuration for the built-in composite controller.
     *
     * @since 0.0.1
     */
    @Getter
    @Setter
    public static class Controller {

        /**
         * Whether the built-in controller is registered.
         *
         * <p>Set {@code composite.controller.enabled=false} to provide your own controller,
         * for example to add custom security annotations or response wrapping.
         * Defaults to {@code true}.
         */
        private boolean enabled = true;
    }

    /**
     * Configuration for composite tracking headers that are injected into each outbound
     * sub-request.
     *
     * <p>When {@code enabled} is {@code true}, every sub-request carries three additional
     * headers that allow handlers to identify the originating composite request and the
     * individual sub-request within it. Use them for tracing and auditing only: a direct
     * client can send the same header names, so they must not be used for authorization.
     *
     * @since 0.0.1
     */
    @Getter
    @Setter
    public static class HeaderInjection {

        /**
         * Whether composite tracking headers are added to each sub-request.
         * Defaults to {@code true}.
         */
        private boolean enabled = true;

        /**
         * Header name used to flag a request as a composite sub-request.
         * Defaults to {@code X-Composite-Request}.
         */
        private String requestHeader = "X-Composite-Request";

        /**
         * Header name carrying the UUID of the parent composite request, grouping all
         * sub-requests that belong to the same batch.
         * Defaults to {@code X-Composite-Request-Id}.
         */
        private String requestIdHeader = "X-Composite-Request-Id";

        /**
         * Header name carrying the {@code referenceId} of this specific sub-request within
         * the batch.
         * Defaults to {@code X-Composite-Sub-Request-Id}.
         */
        private String subRequestIdHeader = "X-Composite-Sub-Request-Id";
    }

    /**
     * Security configuration controlling which headers from the incoming composite request
     * are forwarded to each outbound sub-request.
     *
     * @since 0.0.1
     */
    @Getter
    @Setter
    public static class Security {

        /**
         * Names of request headers to propagate from the outer composite request to every
         * sub-request.
         *
         * <p>Defaults to {@code Authorization} and {@code Cookie}. Setting this property
         * replaces the list. Applications using cookie authentication must keep CSRF
         * protection on the composite endpoint and list their CSRF header here.
         */
        private List<String> forwardedHeaders = new ArrayList<>(List.of("Authorization", "Cookie"));

        /**
         * Names of headers a client may set on an individual sub-request.
         *
         * <p>Any other header is rejected during validation. Credential, proxy, identity,
         * hop-by-hop and composite tracking headers are always refused, even if listed.
         */
        private List<String> clientAllowedHeaders = new ArrayList<>(List.of(
                "Accept", "Accept-Language", "If-Match", "If-None-Match",
                "If-Modified-Since", "If-Unmodified-Since"));

        /**
         * Additional header names clients may never set on a sub-request, such as an
         * application-specific identity header like {@code X-User-Id}.
         */
        private List<String> blockedHeaders = new ArrayList<>();
    }
}
