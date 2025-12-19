package io.github.nabilcarel.composite.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
}
