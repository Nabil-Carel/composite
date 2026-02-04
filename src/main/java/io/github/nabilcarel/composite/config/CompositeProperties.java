package io.github.nabilcarel.composite.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "composite")
@Getter
@Setter
public class CompositeProperties {

    /**
     * Enable the default composite controller. Set to false to provide your own controller.
     */
    private boolean controllerEnabled = true;

    /**
     * Base path for the composite endpoints.
     */
    private String basePath = "/api/composite";

    /**
     * Timeout for entire composite request.
     * Must account for sequential dependencies: if request B depends on A,
     * total time = timeout(A) + timeout(B).
     */
    private Duration requestTimeout = Duration.ofSeconds(60);

    /**
     * Maximum number of sub-requests allowed per composite request.
     */
    private int maxSubRequestsPerComposite = 25;

    /**
     * URL pattern for the composite filter.
     * Should match your composite execution endpoint.
     * Supports Ant-style patterns (e.g., /api/composite/*)
     */
    private String filterPattern = "/api/composite/execute";

    /**
     * Header injection configuration for tracking composite sub-requests.
     */
    private HeaderInjection headerInjection = new HeaderInjection();
    /**
     * Maximum number of resolution iterations for nested placeholders.
     * Prevents infinite loops in circular reference scenarios.
     */
    private int maxResolutionIterations = 10;

    /**
     * Timeout for individual sub-requests.
     * If not set, uses the overall request timeout.
     */
    private Duration subRequestTimeout;

    @Getter
    @Setter
    public static class HeaderInjection {
        /**
         * Enable automatic header injection to identify composite sub-requests.
         */
        private boolean enabled = false;

        /**
         * Header name to mark requests as composite sub-requests.
         */
        private String requestHeader = "X-Composite-Request";

        /**
         * Header name for the composite request ID (groups sub-requests together).
         */
        private String requestIdHeader = "X-Composite-Request-Id";

        /**
         * Header name for individual sub-request ID within a composite request.
         */
        private String subRequestIdHeader = "X-Composite-Sub-Request-Id";
    }

    private Security security = new Security();

    @Getter
    @Setter
    public static class Security {
        /**
         * Headers to forward from the original request to subrequests.
         * Empty by default - you must explicitly specify which headers to forward.
         *
         * Example: Authorization, Cookie, X-API-Key
         */
        private List<String> forwardedHeaders = new ArrayList<>();
    }
}
