package io.github.nabilcarel.composite.autoconfigure;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.ResponseTracker;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnClass(HealthIndicator.class)
@RequiredArgsConstructor
public class CompositeHealthIndicator implements HealthIndicator {
    private final ConcurrentMap<String, ResponseTracker> responseStore;
    private final EndpointRegistry endpointRegistry;

    public Health health(){
        int activeCompositeRequests = responseStore.size();
        int availableEndpoints = endpointRegistry.getAvailableEndpoints().size();

        return Health.up().withDetail("activeCompositeRequests", activeCompositeRequests)
                .withDetail("availableEndpoints", availableEndpoints).build();
    }
}
