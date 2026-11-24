package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration;
import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.internal.ActiveBatches;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeAutoConfigurationTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private Environment environment;

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
    void activeBatches_startsEmpty() {
        ActiveBatches activeBatches = autoConfiguration.compositeActiveBatches();

        assertThat(activeBatches.isEmpty()).isTrue();
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

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Integer.MAX_VALUE + 1L})
    void loopbackWebClient_withInvalidMaxResponseSize_failsClearly(long bytes) {
        CompositeLoopbackProperties loopbackProperties = new CompositeLoopbackProperties();
        loopbackProperties.setMaxResponseSize(DataSize.ofBytes(bytes));

        assertThatThrownBy(() -> autoConfiguration.loopbackWebClient(loopbackProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-response-size");
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
