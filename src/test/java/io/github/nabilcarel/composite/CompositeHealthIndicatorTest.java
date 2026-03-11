package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.autoconfigure.CompositeHealthIndicator;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.ResponseTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeHealthIndicatorTest {

    @Mock
    private EndpointRegistry endpointRegistry;

    private ConcurrentMap<String, ResponseTracker> responseStore;
    private CompositeHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        responseStore = new ConcurrentHashMap<>();
        healthIndicator = new CompositeHealthIndicator(responseStore, endpointRegistry);
    }

    @Test
    void health_returnsUpStatus() {
        when(endpointRegistry.getAvailableEndpoints()).thenReturn(Set.of());

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void health_reportsActiveCompositeRequests() {
        responseStore.put("req1", mock(ResponseTracker.class));
        responseStore.put("req2", mock(ResponseTracker.class));
        when(endpointRegistry.getAvailableEndpoints()).thenReturn(Set.of());

        Health health = healthIndicator.health();

        assertThat(health.getDetails().get("activeCompositeRequests")).isEqualTo(2);
    }

    @Test
    void health_reportsAvailableEndpoints() {
        EndpointRegistry.EndpointInfo info1 = EndpointRegistry.EndpointInfo.builder()
                .pattern("/api/users").method("GET").build();
        EndpointRegistry.EndpointInfo info2 = EndpointRegistry.EndpointInfo.builder()
                .pattern("/api/orders").method("POST").build();
        when(endpointRegistry.getAvailableEndpoints()).thenReturn(Set.of(info1, info2));

        Health health = healthIndicator.health();

        assertThat(health.getDetails().get("availableEndpoints")).isEqualTo(2);
    }

    @Test
    void health_withNoActiveRequestsAndNoEndpoints_reportsZeros() {
        when(endpointRegistry.getAvailableEndpoints()).thenReturn(Set.of());

        Health health = healthIndicator.health();

        assertThat(health.getDetails().get("activeCompositeRequests")).isEqualTo(0);
        assertThat(health.getDetails().get("availableEndpoints")).isEqualTo(0);
    }
}
