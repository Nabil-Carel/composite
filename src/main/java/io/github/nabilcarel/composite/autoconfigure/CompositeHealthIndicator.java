package io.github.nabilcarel.composite.autoconfigure;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.ResponseTracker;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator {@link HealthIndicator} for the Composite library.
 *
 * <p>Registered only when the {@code spring-boot-actuator} dependency is on the classpath.
 * Reports {@code UP} at all times and exposes two detail values:
 * <ul>
 *   <li>{@code activeCompositeRequests} — the number of composite requests currently
 *       in-flight (entries in the shared response store).</li>
 *   <li>{@code availableEndpoints} — the number of endpoints registered for composite
 *       execution.</li>
 * </ul>
 *
 * <p>Access via the standard Actuator health endpoint:
 * <pre class="code">
 * GET /actuator/health
 * </pre>
 *
 * @see io.github.nabilcarel.composite.config.EndpointRegistry
 * @since 0.0.1
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
@RequiredArgsConstructor
public class CompositeHealthIndicator implements HealthIndicator {

    private final ConcurrentMap<String, ResponseTracker> responseStore;
    private final EndpointRegistry endpointRegistry;

    /**
     * Returns the health status of the Composite library.
     *
     * <p>Always reports {@link Health#up() UP}. The detail values can be used for
     * operational monitoring — a persistently high {@code activeCompositeRequests} count
     * may indicate that composite requests are timing out without being cleaned up.
     *
     * @return a {@link Health} instance with composite-specific detail
     */
    @Override
    public Health health(){
        int activeCompositeRequests = responseStore.size();
        int availableEndpoints = endpointRegistry.getAvailableEndpoints().size();

        return Health.up().withDetail("activeCompositeRequests", activeCompositeRequests)
                .withDetail("availableEndpoints", availableEndpoints).build();
    }
}
