package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class EndpointRegistryRoutingTest {

    static class Handlers {
        @CompositeEndpoint
        public void annotated() {
        }

        public void plain() {
        }
    }

    private final Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
    private EndpointRegistry registry;

    @BeforeEach
    void setUp() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        registry = new EndpointRegistry(mock(ApplicationContext.class), handlerMapping);
    }

    private void map(String handler, RequestMethod[] methods, String... paths) throws Exception {
        RequestMappingInfo info = RequestMappingInfo.paths(paths).methods(methods).build();
        mappings.put(info, new HandlerMethod(new Handlers(), Handlers.class.getMethod(handler)));
    }

    private void discover() {
        registry.onApplicationEvent(mock(ApplicationReadyEvent.class));
    }

    private boolean allowed(String method, String url) {
        return registry.getEndpointInformations(method, url).isPresent();
    }

    private static RequestMethod[] get() {
        return new RequestMethod[] {RequestMethod.GET};
    }

    @Test
    void moreSpecificUnannotatedRoute_winsAndIsRejected() throws Exception {
        map("annotated", get(), "/users/{id}");
        map("plain", get(), "/users/me");
        discover();

        assertThat(allowed("GET", "/users/42")).isTrue();
        assertThat(allowed("GET", "/users/me")).isFalse();
    }

    @Test
    void moreSpecificAnnotatedRoute_isAllowedOverUnannotatedPattern() throws Exception {
        map("plain", get(), "/users/{id}");
        map("annotated", get(), "/users/me");
        discover();

        assertThat(allowed("GET", "/users/me")).isTrue();
        assertThat(allowed("GET", "/users/42")).isFalse();
    }

    @Test
    void equallySpecificMappings_mustAllBeAnnotated() throws Exception {
        mappings.put(RequestMappingInfo.paths("/items").methods(RequestMethod.GET).params("a").build(),
            new HandlerMethod(new Handlers(), Handlers.class.getMethod("annotated")));
        mappings.put(RequestMappingInfo.paths("/items").methods(RequestMethod.GET).params("b").build(),
            new HandlerMethod(new Handlers(), Handlers.class.getMethod("plain")));
        discover();

        assertThat(allowed("GET", "/items?a=1")).isFalse();
    }

    @Test
    void everyMappedPath_isRegistered() throws Exception {
        map("annotated", get(), "/first", "/second");
        discover();

        assertThat(allowed("GET", "/first")).isTrue();
        assertThat(allowed("GET", "/second")).isTrue();
        assertThat(registry.getAvailableEndpoints()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "get"})
    void mappingWithoutMethod_acceptsEverySupportedMethod(String method) throws Exception {
        map("annotated", new RequestMethod[0], "/any");
        discover();

        assertThat(allowed(method, "/any")).isTrue();
    }

    @Test
    void wrongMethod_isRejected() throws Exception {
        map("annotated", get(), "/users/{id}");
        discover();

        assertThat(allowed("DELETE", "/users/1")).isFalse();
        assertThat(allowed(null, "/users/1")).isFalse();
    }

    @Test
    void queryStringIsIgnoredForMatching() throws Exception {
        map("annotated", get(), "/items/{id}/details");
        map("plain", get(), "/items/{id}");
        discover();

        assertThat(allowed("GET", "/items/1/details?page=2")).isTrue();
        assertThat(allowed("GET", "/items/1?/details")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example/users/1", "//evil.example/users/1", "/users/../users/1"})
    void unsafeUrls_areRejected(String url) throws Exception {
        map("annotated", get(), "/users/{id}");
        discover();

        assertThat(allowed("GET", url)).isFalse();
    }

    @Test
    void templateIsCheckedWithANeutralValue() throws Exception {
        map("annotated", get(), "/users/{id}");
        map("plain", get(), "/orders/{id}");
        discover();

        assertThat(allowed("GET", "/users/${a.id}")).isTrue();
        assertThat(allowed("GET", "/orders/${a.id}")).isFalse();
    }
}
