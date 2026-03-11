package io.github.nabilcarel.composite.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.filter.CompositeRequestFilter;
import io.github.nabilcarel.composite.controller.CompositeController;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.service.*;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.SSLException;
import org.springframework.core.env.Environment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Spring Boot auto-configuration for the Composite library.
 *
 * <p>This class is the entry point of the auto-configuration chain. It is registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and is active only in
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
 * web application} contexts.
 *
 * <p>The following beans are registered (unless a bean with the same name already exists):
 * <ul>
 *   <li>{@link com.fasterxml.jackson.databind.ObjectMapper compositeObjectMapper} — the
 *       {@code ObjectMapper} used for serialising/deserialising composite payloads.
 *       Customise by declaring a bean named {@code compositeObjectMapper}.</li>
 *   <li>{@link org.springframework.web.reactive.function.client.WebClient compositeWebClient}
 *       — the reactive WebClient used for loopback sub-requests, configured from
 *       {@link CompositeLoopbackProperties}. Customise by declaring a bean named
 *       {@code compositeWebClient}.</li>
 *   <li>A {@link java.util.concurrent.ConcurrentMap} keyed by request ID, acting as the
 *       in-flight
 *       {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker}
 *       store.</li>
 *   <li>A {@link org.springframework.boot.web.servlet.FilterRegistrationBean} for the
 *       {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter
 *       CompositeRequestFilter}.</li>
 * </ul>
 *
 * <p>The {@code compositeWebClient} bean is created lazily and resolves the loopback port
 * from the {@link org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent
 * ServletWebServerInitializedEvent}.
 *
 * @see CompositeProperties
 * @see CompositeLoopbackProperties
 * @since 0.0.1
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnWebApplication
@EnableConfigurationProperties({CompositeLoopbackProperties.class, CompositeProperties.class})
@Slf4j
@Import({
        CompositeRequestFilter.class,
        CompositeController.class,
        EndpointRegistry.class,
        AuthenticationForwardingServiceImpl.class,
        CompositeRequestServiceImpl.class,
        CompositeRequestValidatorImpl.class,
        ReferenceResolverServiceImpl.class
})
public class CompositeAutoConfiguration implements ApplicationListener<ServletWebServerInitializedEvent> {
    private final CompositeProperties properties;
    private final Environment environment;
    private int serverPort;


    /**
     * Provides the {@link ObjectMapper} used by the composite library for JSON
     * serialisation and deserialisation.
     *
     * <p>To customise the mapper, declare a bean named {@code compositeObjectMapper} in
     * your application context — for example to register additional modules or configure
     * a custom date format.
     *
     * @return a default {@link ObjectMapper} instance
     */
    @Bean("compositeObjectMapper")
    @ConditionalOnMissingBean(name = "compositeObjectMapper")
    public ObjectMapper objectMapper(){
        return new ObjectMapper();
    }

    /**
     * Registers the {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter
     * CompositeRequestFilter} as a servlet filter.
     *
     * <p>The filter is mapped to the URL pattern defined by
     * {@code composite.filter-pattern} and runs near the end of the filter
     * chain ({@link Ordered#LOWEST_PRECEDENCE} minus one).
     *
     * @param filter the filter bean to register
     * @return the configured {@link FilterRegistrationBean}
     */
    @Bean
    public FilterRegistrationBean<CompositeRequestFilter> compositeFilter(CompositeRequestFilter filter) {
        FilterRegistrationBean<CompositeRequestFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(filter);
        registrationBean.addUrlPatterns(properties.getFilterPattern());
        registrationBean.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return registrationBean;
    }

    /**
     * Provides the shared, thread-safe map used to store in-flight
     * {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker} instances,
     * keyed by composite request UUID.
     *
     * @return a new {@link ConcurrentHashMap}
     */
    @Bean
    public ConcurrentMap<String, ResponseTracker> responseStore(){
        return new ConcurrentHashMap<>();
    }

    /**
     * Captures the server port once the embedded web server has started so that the lazily
     * created {@link #loopbackWebClient} can construct the correct base URL.
     *
     * {@inheritDoc}
     */
    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
        log.info("Composite endpoint library initialized on port {}", serverPort);
        if (properties.isDebugEnabled()) {
            log.warn("Composite debug mode is enabled. Disable in production.");
        }
    }

    /**
     * Provides the loopback {@link WebClient} used to dispatch sub-requests back to the
     * same application instance.
     *
     * <p>The bean is {@link org.springframework.context.annotation.Lazy @Lazy} so that it
     * is not created until the first composite request arrives — by which point the server
     * port will have been captured via
     * {@link #onApplicationEvent(ServletWebServerInitializedEvent)}.
     *
     * <p>Override by declaring a bean named {@code compositeWebClient} in your application
     * context.
     *
     * @param loopbackProperties loopback configuration (protocol, timeouts, SSL)
     * @return a configured {@link WebClient} targeting {@code {protocol}://localhost:{port}}
     */
    @Bean("compositeWebClient")
    @Lazy
    @ConditionalOnMissingBean(name = "compositeWebClient")
    public WebClient loopbackWebClient(CompositeLoopbackProperties loopbackProperties) {
        String protocol = loopbackProperties.getProtocol();
        String baseUrl = String.format("%s://localhost:%d", protocol, serverPort);
        log.info("Creating WebClient with baseUrl: {}", baseUrl);

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) loopbackProperties.getConnectTimeout().toMillis())
                .responseTimeout(loopbackProperties.getResponseTimeout());

        if ("https".equalsIgnoreCase(protocol)) {
            httpClient = configureHttpsClient(httpClient, loopbackProperties);
        }

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private HttpClient configureHttpsClient(HttpClient httpClient, CompositeLoopbackProperties loopbackProperties) {
        boolean serverSslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);

        if (!serverSslEnabled) {
            log.warn("""
                    Composite loopback configured for HTTPS but server SSL is not enabled.
                    Either:
                      - Set server.ssl.enabled=true with a valid keystore
                      - Or change composite.loopback.protocol=http for unencrypted loopback
                    Attempting to proceed, but connections will likely fail.""");
        }

        if (loopbackProperties.isTrustSelfSignedCertificates()) {
            log.warn("Composite loopback is configured to trust self-signed certificates. " +
                    "This should only be used in development/testing environments.");
            try {
                SslContext sslContext = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
                return httpClient.secure(spec -> spec.sslContext(sslContext));
            } catch (SSLException e) {
                throw new IllegalStateException("Failed to configure SSL context for self-signed certificates", e);
            }
        } else {
            log.info("""
                    Composite loopback using HTTPS with default truststore.
                    If using self-signed certificates, either:
                      - Import your certificate into the JVM truststore (recommended for production)
                      - Or set composite.loopback.trust-self-signed-certificates=true (dev/test only)""");
        }

        return httpClient;
    }
}
