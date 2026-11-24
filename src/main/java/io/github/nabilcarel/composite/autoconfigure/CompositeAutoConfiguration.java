package io.github.nabilcarel.composite.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.controller.CompositeController;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import io.github.nabilcarel.composite.internal.WebClientSubRequestDispatcher;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.service.*;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import org.springframework.core.env.Environment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriBuilderFactory;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

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
 *   <li>{@link SubRequestDispatcher} — sends loopback sub-requests. Declare your own bean to
 *       replace the default HTTP client.</li>
 *   <li>The validator, reference resolver, credential forwarding service and composite
 *       service, each replaceable by declaring a bean of its interface type.</li>
 *   <li>A {@link java.util.concurrent.ConcurrentMap} keyed by request ID, acting as the
 *       in-flight
 *       {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker}
 *       store.</li>
 *   <li>A {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer} that
 *       registers the {@link CompositeDispatchInterceptor}.</li>
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
@AutoConfiguration(after = {
        JacksonAutoConfiguration.class,
        ValidationAutoConfiguration.class,
        WebMvcAutoConfiguration.class
})
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({CompositeLoopbackProperties.class, CompositeProperties.class})
@Slf4j
@Import(CompositeController.class)
public class CompositeAutoConfiguration implements ApplicationListener<ServletWebServerInitializedEvent> {
    private final CompositeProperties properties;
    private final Environment environment;
    private volatile int serverPort;
    private volatile ConnectionProvider connectionProvider;

    /** Releases loopback connections when the application context closes. */
    @PreDestroy
    void closeConnectionProvider() {
        if (connectionProvider != null) {
            connectionProvider.dispose();
        }
    }


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
     * Registers the interceptor that verifies the handler selected for each loopback
     * sub-request.
     *
     * @return the MVC configurer
     */
    @Bean
    public WebMvcConfigurer compositeDispatchConfigurer(CompositeLoopbackProperties loopbackProperties) {
        int maxConcurrentHandlers = effectiveMaxConnections(loopbackProperties);

        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new CompositeDispatchInterceptor(maxConcurrentHandlers));
            }
        };
    }

    private int effectiveMaxConnections(CompositeLoopbackProperties loopbackProperties) {
        Integer configuredThreads = environment.getProperty("server.tomcat.threads.max", Integer.class, 200);
        int serverThreads = configuredThreads == null ? 200 : configuredThreads;

        if (loopbackProperties.getMaxConnections() == null) {
            return Math.max(1, serverThreads / 2);
        }

        if (loopbackProperties.getMaxConnections() >= serverThreads) {
            log.warn("composite.loopback.max-connections ({}) leaves no Tomcat threads for normal traffic ({})",
                    loopbackProperties.getMaxConnections(), serverThreads);
        }
        return loopbackProperties.getMaxConnections();
    }

    /**
     * Provides the shared, thread-safe map used to store in-flight
     * {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker} instances,
     * keyed by composite request UUID.
     *
     * @return a new {@link ConcurrentHashMap}
     */
    @Bean("compositeResponseStore")
    public ConcurrentMap<String, ResponseTracker> responseStore(){
        return new ConcurrentHashMap<>();
    }

    /**
     * Discovers the endpoints available to composite requests.
     *
     * @param context the application context
     * @param handlerMapping Spring MVC's request mapping
     * @return the endpoint registry
     */
    @Bean
    @ConditionalOnMissingBean
    public EndpointRegistry compositeEndpointRegistry(
            ApplicationContext context,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        return new EndpointRegistry(context, handlerMapping);
    }

    /**
     * Copies configured credentials from the outer request.
     *
     * @return the default forwarding service
     */
    @Bean
    @ConditionalOnMissingBean
    public AuthenticationForwardingService compositeAuthenticationForwardingService() {
        return new AuthenticationForwardingServiceImpl(properties);
    }

    /**
     * Validates composite requests.
     *
     * @param validator the Bean Validation validator
     * @param endpointRegistry the endpoint registry
     * @return the default validator
     */
    @Bean
    @ConditionalOnMissingBean
    public CompositeRequestValidator compositeRequestValidator(Validator validator,
                                                               EndpointRegistry endpointRegistry) {
        return new CompositeRequestValidatorImpl(validator, endpointRegistry, properties);
    }

    /**
     * Resolves references between sub-requests.
     *
     * @param responseStore in-flight responses
     * @param objectMapper the composite object mapper
     * @return the default resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public ReferenceResolverService compositeReferenceResolver(
            ConcurrentMap<String, ResponseTracker> responseStore,
            @Qualifier("compositeObjectMapper") ObjectMapper objectMapper) {
        return new ReferenceResolverServiceImpl(responseStore, objectMapper, properties);
    }

    /**
     * Sends sub-requests through the loopback client.
     *
     * @param webClient the lazily created loopback client
     * @return the default dispatcher
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public SubRequestDispatcher compositeSubRequestDispatcher(
            @Lazy @Qualifier("compositeWebClient") WebClient webClient) {
        return new WebClientSubRequestDispatcher(webClient);
    }

    /**
     * Executes composite requests.
     *
     * @param endpointRegistry the endpoint registry
     * @param objectMapper the composite object mapper
     * @param responseStore in-flight responses
     * @param validator the request validator
     * @param referenceResolver the reference resolver
     * @param authenticationForwardingService the credential forwarding service
     * @param dispatcher the sub-request dispatcher
     * @return the default service
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public CompositeRequestService compositeRequestService(
            EndpointRegistry endpointRegistry,
            @Qualifier("compositeObjectMapper") ObjectMapper objectMapper,
            ConcurrentMap<String, ResponseTracker> responseStore,
            CompositeRequestValidator validator,
            ReferenceResolverService referenceResolver,
            AuthenticationForwardingService authenticationForwardingService,
            @Lazy SubRequestDispatcher dispatcher) {
        return new CompositeRequestServiceImpl(endpointRegistry, objectMapper, responseStore, validator,
                referenceResolver, properties, authenticationForwardingService, dispatcher);
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
     * <p>This client is an implementation detail. Declare a {@link SubRequestDispatcher} bean
     * to replace how sub-requests are sent.
     *
     * @param loopbackProperties loopback configuration (protocol, timeouts, SSL)
     * @return a configured {@link WebClient} targeting the application's local URL, including
     *         its context path and Spring MVC servlet path
     */
    @Bean("compositeWebClient")
    @Lazy
    @ConditionalOnMissingBean(name = "compositeWebClient")
    public WebClient loopbackWebClient(CompositeLoopbackProperties loopbackProperties) {
        int maxResponseSize = toBufferLimit(loopbackProperties.getMaxResponseSize());
        String protocol = loopbackProperties.getProtocol();

        connectionProvider = ConnectionProvider.builder("composite-loopback")
                .maxConnections(effectiveMaxConnections(loopbackProperties))
                .pendingAcquireMaxCount(-1)
                .pendingAcquireTimeout(properties.getRequestTimeout())
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) loopbackProperties.getConnectTimeout().toMillis());

        if (loopbackProperties.getResponseTimeout() != null) {
            httpClient = httpClient.responseTimeout(loopbackProperties.getResponseTimeout());
        }

        if ("https".equalsIgnoreCase(protocol)) {
            httpClient = configureHttpsClient(httpClient, loopbackProperties);
        }

        // A custom controller can create this client before the server has chosen its port.
        UriBuilderFactory uriBuilderFactory = new LoopbackUriBuilderFactory(() -> loopbackBaseUrl(protocol));

        return WebClient.builder()
                .uriBuilderFactory(uriBuilderFactory)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseSize))
                .build();
    }

    /**
     * Registers the health indicator only when Spring Boot Actuator is present.
     *
     * @since 0.0.1
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class CompositeHealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "compositeHealthIndicator")
        CompositeHealthIndicator compositeHealthIndicator(ConcurrentMap<String, ResponseTracker> responseStore,
                                                          EndpointRegistry endpointRegistry) {
            return new CompositeHealthIndicator(responseStore, endpointRegistry);
        }
    }

    private String loopbackBaseUrl(String protocol) {
        if (serverPort <= 0) {
            throw new IllegalStateException("Composite loopback used before the web server started");
        }

        String baseUrl = String.format("%s://localhost:%d%s%s", protocol, serverPort,
                normalizePathPrefix(environment.getProperty("server.servlet.context-path")),
                normalizePathPrefix(environment.getProperty("spring.mvc.servlet.path")));
        log.info("Composite loopback base URL: {}", baseUrl);
        return baseUrl;
    }

    /**
     * Resolves the loopback base URL on first use. Sub-request URLs are already encoded by
     * {@code SubRequestTarget}, so they are not encoded again.
     */
    private static final class LoopbackUriBuilderFactory implements UriBuilderFactory {

        private final Supplier<String> baseUrl;
        private volatile DefaultUriBuilderFactory delegate;

        private LoopbackUriBuilderFactory(Supplier<String> baseUrl) {
            this.baseUrl = baseUrl;
        }

        private DefaultUriBuilderFactory delegate() {
            DefaultUriBuilderFactory current = delegate;
            if (current == null) {
                current = new DefaultUriBuilderFactory(baseUrl.get());
                current.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
                delegate = current;
            }
            return current;
        }

        @Override
        public UriBuilder uriString(String uriTemplate) {
            return delegate().uriString(uriTemplate);
        }

        @Override
        public UriBuilder builder() {
            return delegate().builder();
        }

        @Override
        public URI expand(String uriTemplate, Map<String, ?> uriVariables) {
            return delegate().expand(uriTemplate, uriVariables);
        }

        @Override
        public URI expand(String uriTemplate, Object... uriVariables) {
            return delegate().expand(uriTemplate, uriVariables);
        }
    }

    private static String normalizePathPrefix(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        String normalized = path.trim();
        if (normalized.endsWith("/*")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static int toBufferLimit(DataSize maxResponseSize) {
        if (maxResponseSize == null
                || maxResponseSize.toBytes() < 1
                || maxResponseSize.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "composite.loopback.max-response-size must be between 1B and " + Integer.MAX_VALUE + "B");
        }
        return (int) maxResponseSize.toBytes();
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
