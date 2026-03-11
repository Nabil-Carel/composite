package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EndpointRegistryTest {

    @Mock
    private ApplicationContext applicationContext;
    @Mock
    private RequestMappingHandlerMapping handlerMapping;

    private EndpointRegistry endpointRegistry;

    @BeforeEach
    void setUp() {
        endpointRegistry = new EndpointRegistry(applicationContext, handlerMapping);
    }

    @Test
    void getEndpointInformations_withNoRegisteredEndpoints_returnsEmpty() {
        Optional<EndpointInfo> result = endpointRegistry.getEndpointInformations("GET", "/api/users");

        assertThat(result).isEmpty();
    }

    @Test
    void getEndpointInformations_afterDiscovery_returnsMatchingEndpoint() throws Exception {
        setupEndpointDiscovery("/api/users/{id}", RequestMethod.GET, String.class);

        triggerDiscovery();

        Optional<EndpointInfo> result = endpointRegistry.getEndpointInformations("GET", "/api/users/123");

        assertThat(result).isPresent();
        assertThat(result.get().getPattern()).isEqualTo("/api/users/{id}");
        assertThat(result.get().getMethod()).isEqualTo("GET");
        assertThat(result.get().getReturnClass()).isEqualTo(String.class);
    }

    @Test
    void getEndpointInformations_withWrongMethod_returnsEmpty() throws Exception {
        setupEndpointDiscovery("/api/users/{id}", RequestMethod.GET, String.class);

        triggerDiscovery();

        Optional<EndpointInfo> result = endpointRegistry.getEndpointInformations("POST", "/api/users/123");

        assertThat(result).isEmpty();
    }

    @Test
    void getEndpointInformations_withNonMatchingPath_returnsEmpty() throws Exception {
        setupEndpointDiscovery("/api/users/{id}", RequestMethod.GET, String.class);

        triggerDiscovery();

        Optional<EndpointInfo> result = endpointRegistry.getEndpointInformations("GET", "/api/orders/123");

        assertThat(result).isEmpty();
    }

    @Test
    void getAvailableEndpoints_withNoRegisteredEndpoints_returnsEmptySet() {
        Set<EndpointInfo> endpoints = endpointRegistry.getAvailableEndpoints();

        assertThat(endpoints).isEmpty();
    }

    @Test
    void getAvailableEndpoints_afterDiscovery_returnsAllEndpoints() throws Exception {
        setupEndpointDiscovery("/api/users/{id}", RequestMethod.GET, String.class);

        triggerDiscovery();

        Set<EndpointInfo> endpoints = endpointRegistry.getAvailableEndpoints();

        assertThat(endpoints).hasSize(1);
    }

    @Test
    void getEndpointInformations_withMethodCaseInsensitive_matchesEndpoint() throws Exception {
        setupEndpointDiscovery("/api/users", RequestMethod.GET, String.class);

        triggerDiscovery();

        Optional<EndpointInfo> result = endpointRegistry.getEndpointInformations("get", "/api/users");

        assertThat(result).isPresent();
    }

    @Test
    void getEndpointInformations_withLeadingSlash_matchesCorrectly() throws Exception {
        setupEndpointDiscovery("/api/users/{id}", RequestMethod.GET, String.class);

        triggerDiscovery();

        Optional<EndpointInfo> withSlash = endpointRegistry.getEndpointInformations("GET", "/api/users/1");

        assertThat(withSlash).isPresent();
    }

    // ========== Helper Methods ==========

    @CompositeEndpoint(String.class)
    public String dummyEndpoint() {
        return "";
    }

    private void setupEndpointDiscovery(String pattern, RequestMethod method, Class<?> returnType) throws Exception {
        Method dummyMethod = this.getClass().getMethod("dummyEndpoint");
        HandlerMethod handlerMethod = mock(HandlerMethod.class);

        CompositeEndpoint annotation = dummyMethod.getAnnotation(CompositeEndpoint.class);
        when(handlerMethod.getMethodAnnotation(CompositeEndpoint.class)).thenReturn(annotation);

        PathPatternsRequestCondition pathCondition = new PathPatternsRequestCondition( new PathPatternParser(), pattern);
        RequestMethodsRequestCondition methodCondition = new RequestMethodsRequestCondition(method);

        RequestMappingInfo mappingInfo = mock(RequestMappingInfo.class);
        when(mappingInfo.getPathPatternsCondition()).thenReturn(pathCondition);
        when(mappingInfo.getMethodsCondition()).thenReturn(methodCondition);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));
    }

    private void triggerDiscovery() {
        org.springframework.boot.context.event.ApplicationReadyEvent event =
                mock(org.springframework.boot.context.event.ApplicationReadyEvent.class);
        endpointRegistry.onApplicationEvent(event);
    }
}
