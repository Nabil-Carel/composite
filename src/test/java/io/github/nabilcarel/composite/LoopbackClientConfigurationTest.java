package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import io.github.nabilcarel.composite.internal.JdkHttpSubRequestDispatcher;
import io.github.nabilcarel.composite.internal.LoopbackAddress;
import io.github.nabilcarel.composite.internal.LoopbackTrust;
import io.github.nabilcarel.composite.internal.WebClientSubRequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

/** Chooses the loopback client and keeps WebFlux optional. */
class LoopbackClientConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            ValidationAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            CompositeAutoConfiguration.class));

    /** The {@code -PloopbackClient} build switch sets this, and these tests need the real default. */
    private static final String CLIENT_PROPERTY = "composite.loopback.client";
    private String savedClient;

    @BeforeEach
    void clearBuildSwitch() {
        savedClient = System.clearProperty(CLIENT_PROPERTY);
    }

    @AfterEach
    void restoreBuildSwitch() {
        if (savedClient != null) {
            System.setProperty(CLIENT_PROPERTY, savedClient);
        }
    }

    @Test
    void jdkClient_isTheDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SubRequestDispatcher.class)).isInstanceOf(JdkHttpSubRequestDispatcher.class);
            assertThat(context).doesNotHaveBean("compositeWebClient");
        });
    }

    @Test
    void webClient_canBeChosen() {
        runner.withPropertyValues("composite.loopback.client=webclient").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SubRequestDispatcher.class)).isInstanceOf(WebClientSubRequestDispatcher.class);
            assertThat(context.getBean("compositeWebClient")).isInstanceOf(WebClient.class);
        });
    }

    @Test
    void jdkClient_worksWithoutWebFluxOrNetty() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.web.reactive", "reactor.netty", "io.netty"))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(SubRequestDispatcher.class))
                    .isInstanceOf(JdkHttpSubRequestDispatcher.class);
            });
    }

    @Test
    void webClientWithoutWebFlux_failsWithAClearMessage() {
        runner.withPropertyValues("composite.loopback.client=webclient")
            .withClassLoader(new FilteredClassLoader("org.springframework.web.reactive", "reactor.netty", "io.netty"))
            .run(context -> assertThat(context).getFailure()
                .rootCause().hasMessageContaining("requires spring-boot-starter-webflux"));
    }

    @Test
    void customDispatcher_winsOverEitherClient() {
        SubRequestDispatcher custom = mock(SubRequestDispatcher.class);

        runner.withPropertyValues("composite.loopback.client=webclient")
            .withBean(SubRequestDispatcher.class, () -> custom)
            .run(context -> assertThat(context.getBean(SubRequestDispatcher.class)).isSameAs(custom));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdk", "webclient"})
    void httpsWithoutServerSsl_createsTheClientWithJvmTrust(String client) {
        runner.withPropertyValues("composite.loopback.client=" + client, "composite.loopback.protocol=https")
            .run(context -> {
                assertThat(context.getBean(SubRequestDispatcher.class)).isNotNull();
                assertThat(context.getBeanProvider(LoopbackTrust.class).getIfAvailable()).isNull();
            });
    }

    @Test
    void unknownSslBundle_failsAtStartup() {
        runner.withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
            .withPropertyValues("composite.loopback.protocol=https", "composite.loopback.ssl-bundle=missing")
            .run(context -> assertThat(context).getFailure()
                .rootCause().isInstanceOf(NoSuchSslBundleException.class).hasMessageContaining("missing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdk", "webclient"})
    void invalidMaxResponseSize_failsClearly(String client) {
        runner.withPropertyValues("composite.loopback.client=" + client, "composite.loopback.max-response-size=0B")
            .run(context -> {
                // The WebClient dispatcher receives a lazy proxy, so the client itself validates the size.
                Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> context.getBean(
                    client.equals("jdk") ? "compositeSubRequestDispatcher" : "compositeWebClient"));
                assertThat(failure).rootCause().hasMessageContaining("max-response-size");
            });
    }

    @Test
    void loopbackAddress_includesContextAndServletPaths_andIgnoresTheManagementServer() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("server.servlet.context-path", "/app/")
            .withProperty("spring.mvc.servlet.path", "/mvc/*");
        LoopbackAddress address = new LoopbackAddress("http", environment);

        address.onApplicationEvent(event(8080, null));
        address.onApplicationEvent(event(9090, "management"));

        assertThat(address.baseUrl()).isEqualTo("http://localhost:8080/app/mvc");
    }

    private static ServletWebServerInitializedEvent event(int port, String namespace) {
        ServletWebServerInitializedEvent event = mock(ServletWebServerInitializedEvent.class);
        WebServer server = mock(WebServer.class);
        ServletWebServerApplicationContext context = mock(ServletWebServerApplicationContext.class);
        when(event.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(port);
        when(event.getApplicationContext()).thenReturn(context);
        when(context.getServerNamespace()).thenReturn(namespace);
        return event;
    }
}
