package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration;
import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.filter.CompositeRequestFilter;
import io.github.nabilcarel.composite.model.ResponseTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeAutoConfigurationTest {

    @Mock
    private Environment environment;
    @Mock
    private CompositeRequestFilter compositeRequestFilter;

    private CompositeProperties properties;
    private CompositeAutoConfiguration autoConfiguration;

    @BeforeEach
    void setUp() {
        properties = new CompositeProperties();
        autoConfiguration = new CompositeAutoConfiguration(properties, environment);
    }

    @Test
    void objectMapper_createsNewObjectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = autoConfiguration.objectMapper();

        assertThat(mapper).isNotNull();
    }

    @Test
    void compositeFilter_registersFilterWithConfiguredPattern() {
        properties.setFilterPattern("/api/composite/execute");

        FilterRegistrationBean<CompositeRequestFilter> registration =
                autoConfiguration.compositeFilter(compositeRequestFilter);

        assertThat(registration.getFilter()).isSameAs(compositeRequestFilter);
        assertThat(registration.getUrlPatterns()).contains("/api/composite/execute");
        assertThat(registration.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 1);
    }

    @Test
    void compositeFilter_withCustomPattern_usesCustomPattern() {
        properties.setFilterPattern("/custom/path/*");

        FilterRegistrationBean<CompositeRequestFilter> registration =
                autoConfiguration.compositeFilter(compositeRequestFilter);

        assertThat(registration.getUrlPatterns()).contains("/custom/path/*");
    }

    @Test
    void responseStore_createsConcurrentMap() {
        ConcurrentMap<String, ResponseTracker> store = autoConfiguration.responseStore();

        assertThat(store).isNotNull().isEmpty();
    }

    @Test
    void onApplicationEvent_capturesServerPort() {
        ServletWebServerInitializedEvent event = mock(ServletWebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(8080);

        autoConfiguration.onApplicationEvent(event);

        // Verify port is captured by creating a WebClient (indirectly tests port usage)
        // The port is stored internally and used when creating the WebClient bean
        // We verify the event handler doesn't throw
    }

    @Test
    void loopbackWebClient_withHttpProtocol_createsWebClient() {
        // Setup: simulate server port initialization
        ServletWebServerInitializedEvent event = mock(ServletWebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(8080);
        autoConfiguration.onApplicationEvent(event);

        CompositeLoopbackProperties loopbackProperties = new CompositeLoopbackProperties();
        loopbackProperties.setProtocol("http");

        org.springframework.web.reactive.function.client.WebClient webClient =
                autoConfiguration.loopbackWebClient(loopbackProperties);

        assertThat(webClient).isNotNull();
    }

    @Test
    void loopbackWebClient_withHttpsAndSelfSigned_createsWebClient() {
        ServletWebServerInitializedEvent event = mock(ServletWebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(8443);
        autoConfiguration.onApplicationEvent(event);

        when(environment.getProperty("server.ssl.enabled", Boolean.class, false)).thenReturn(true);

        CompositeLoopbackProperties loopbackProperties = new CompositeLoopbackProperties();
        loopbackProperties.setProtocol("https");
        loopbackProperties.setTrustSelfSignedCertificates(true);

        org.springframework.web.reactive.function.client.WebClient webClient =
                autoConfiguration.loopbackWebClient(loopbackProperties);

        assertThat(webClient).isNotNull();
    }

    @Test
    void loopbackWebClient_withHttpsAndDefaultTruststore_createsWebClient() {
        ServletWebServerInitializedEvent event = mock(ServletWebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(8443);
        autoConfiguration.onApplicationEvent(event);

        when(environment.getProperty("server.ssl.enabled", Boolean.class, false)).thenReturn(true);

        CompositeLoopbackProperties loopbackProperties = new CompositeLoopbackProperties();
        loopbackProperties.setProtocol("https");
        loopbackProperties.setTrustSelfSignedCertificates(false);

        org.springframework.web.reactive.function.client.WebClient webClient =
                autoConfiguration.loopbackWebClient(loopbackProperties);

        assertThat(webClient).isNotNull();
    }

    @Test
    void loopbackWebClient_withHttpsButSslNotEnabled_stillCreatesWebClient() {
        ServletWebServerInitializedEvent event = mock(ServletWebServerInitializedEvent.class);
        WebServer webServer = mock(WebServer.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(8443);
        autoConfiguration.onApplicationEvent(event);

        when(environment.getProperty("server.ssl.enabled", Boolean.class, false)).thenReturn(false);

        CompositeLoopbackProperties loopbackProperties = new CompositeLoopbackProperties();
        loopbackProperties.setProtocol("https");
        loopbackProperties.setTrustSelfSignedCertificates(false);

        org.springframework.web.reactive.function.client.WebClient webClient =
                autoConfiguration.loopbackWebClient(loopbackProperties);

        assertThat(webClient).isNotNull();
    }
}
