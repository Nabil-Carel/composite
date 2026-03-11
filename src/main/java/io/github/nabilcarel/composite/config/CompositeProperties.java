package io.github.nabilcarel.composite.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
@Getter
@Setter
public class CompositeProperties {

    /**
     * Whether the built-in {@link io.github.nabilcarel.composite.controller.CompositeController}
     * is registered.
     *
     * <p>Set to {@code false} to disable the default controller and provide your own, for
     * example when you need to add custom security annotations or response wrapping.
     * Defaults to {@code true}.
     */
    private boolean controllerEnabled = true;

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
     * Ant-style URL pattern used to register the
     * {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter}.
     *
     * <p>Must match the URL of the composite execution endpoint. Defaults to
     * {@code /api/composite/execute}.
     */
    private String filterPattern = "/api/composite/execute";

    /**
     * Configuration for composite-request tracking headers injected into each sub-request.
     *
     * @see HeaderInjection
     */
    private HeaderInjection headerInjection = new HeaderInjection();

    /**
     * Maximum number of placeholder-resolution iterations permitted per value.
     *
     * <p>Limits the depth to which nested {@code ${...}} expressions (e.g.
     * {@code ${${other.key}.name}}) are expanded. Guards against pathological inputs that
     * would otherwise cause infinite resolution loops. Defaults to {@code 10}.
     */
    private int maxResolutionIterations = 10;

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
     * Configuration for composite tracking headers that are injected into each outbound
     * sub-request.
     *
     * <p>When {@code enabled} is {@code true}, every sub-request carries three additional
     * headers that allow downstream services to identify the originating composite request
     * and the individual sub-request within it.
     *
     * @since 0.0.1
     */
    @Getter
    @Setter
    public static class HeaderInjection {

        /**
         * Whether composite tracking headers are added to each sub-request.
         * Defaults to {@code false}.
         */
        private boolean enabled = false;

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
         * <p>Empty by default — headers are <em>not</em> forwarded unless explicitly listed
         * here. Common values include {@code Authorization}, {@code Cookie}, and
         * {@code X-API-Key}.
         */
        private List<String> forwardedHeaders = new ArrayList<>();
    }
}
