package io.github.nabilcarel.composite.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.controller.CompositeController;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import io.github.nabilcarel.composite.internal.ActiveBatches;
import io.github.nabilcarel.composite.internal.CompositeRequestBodyExceptionResolver;
import io.github.nabilcarel.composite.internal.CompositeRequestSizeAdvice;
import io.github.nabilcarel.composite.internal.DispatchMarker;
import io.github.nabilcarel.composite.internal.JdkHttpSubRequestDispatcher;
import io.github.nabilcarel.composite.internal.LoopbackAddress;
import io.github.nabilcarel.composite.service.*;
import jakarta.validation.Validator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.springframework.core.env.Environment;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
 *   <li>{@link SubRequestDispatcher} — sends loopback sub-requests with the non-blocking JDK
 *       HTTP client, or with WebClient when {@code composite.loopback.client=webclient}.
 *       Declare your own bean to replace it.</li>
 *   <li>The validator, reference resolver, credential forwarding service and composite
 *       service, each replaceable by declaring a bean of its interface type.</li>
 *   <li>A {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer} that
 *       registers the {@link CompositeDispatchInterceptor}.</li>
 * </ul>
 *
 * <p>The dispatcher is created lazily and reads the loopback port from the
 * {@link org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent
 * ServletWebServerInitializedEvent}. WebFlux is optional and only needed for the WebClient
 * option.
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
@Import({CompositeController.class, WebClientLoopbackConfiguration.class})
public class CompositeAutoConfiguration implements ApplicationListener<ServletWebServerInitializedEvent> {
    private final CompositeProperties properties;
    private final Environment environment;

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
     * @param loopbackProperties loopback limits
     * @param marker identifies genuine loopback calls
     * @param objectMapper the composite object mapper
     * @return the MVC configurer
     */
    @Bean
    public WebMvcConfigurer compositeDispatchConfigurer(
            CompositeLoopbackProperties loopbackProperties,
            DispatchMarker marker,
            @Qualifier("compositeObjectMapper") ObjectMapper objectMapper) {
        int maxConcurrentHandlers = LoopbackSupport.effectiveMaxConnections(environment, loopbackProperties);

        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new CompositeDispatchInterceptor(maxConcurrentHandlers, marker));
            }

            @Override
            public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                resolvers.add(0, new CompositeRequestBodyExceptionResolver(objectMapper));
            }
        };
    }

    /**
     * Provides the random value that identifies genuine loopback calls.
     *
     * @return the dispatch marker
     */
    @Bean
    public DispatchMarker compositeDispatchMarker() {
        return new DispatchMarker();
    }

    /**
     * Enforces {@code composite.max-request-size} while composite bodies are read.
     *
     * @return the request body advice
     */
    @Bean
    public CompositeRequestSizeAdvice compositeRequestSizeAdvice() {
        return new CompositeRequestSizeAdvice(properties);
    }

    /**
     * Counts running composite requests for health reporting.
     *
     * @return the counter
     */
    @Bean
    public ActiveBatches compositeActiveBatches() {
        return new ActiveBatches();
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
     * @param objectMapper the composite object mapper
     * @return the default resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public ReferenceResolverService compositeReferenceResolver(
            @Qualifier("compositeObjectMapper") ObjectMapper objectMapper) {
        return new ReferenceResolverServiceImpl(objectMapper, properties);
    }

    /**
     * Provides the application's own base URL for loopback calls.
     *
     * @param loopbackProperties loopback configuration
     * @return the loopback address
     */
    @Bean
    public LoopbackAddress compositeLoopbackAddress(CompositeLoopbackProperties loopbackProperties) {
        return new LoopbackAddress(loopbackProperties.getProtocol(), environment);
    }

    /**
     * Sends sub-requests with the non-blocking JDK HTTP client, the default
     * {@code composite.loopback.client}.
     *
     * @param loopbackProperties loopback configuration (protocol, timeouts, SSL, limits)
     * @param address the application's base URL
     * @param objectMapper serialises request bodies
     * @return the default dispatcher
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "composite.loopback", name = "client", havingValue = "jdk", matchIfMissing = true)
    public SubRequestDispatcher compositeSubRequestDispatcher(
            CompositeLoopbackProperties loopbackProperties,
            LoopbackAddress address,
            @Qualifier("compositeObjectMapper") ObjectMapper objectMapper) {
        int maxResponseSize = LoopbackSupport.toBufferLimit(loopbackProperties.getMaxResponseSize());
        SSLContext sslContext = null;

        if (LoopbackSupport.isHttps(loopbackProperties)) {
            LoopbackSupport.logHttpsSetup(environment, loopbackProperties);
            if (loopbackProperties.isTrustSelfSignedCertificates()) {
                sslContext = LoopbackSupport.trustAllSslContext();
            }
        }

        return new JdkHttpSubRequestDispatcher(address::baseUrl, objectMapper, loopbackExecutor(),
                loopbackProperties.getConnectTimeout(), loopbackProperties.getResponseTimeout(), maxResponseSize,
                LoopbackSupport.effectiveMaxConnections(environment, loopbackProperties), sslContext);
    }

    /**
     * Completes responses. The work never blocks, so a few platform threads serve every call,
     * or virtual threads when {@code spring.threads.virtual.enabled=true}.
     */
    private Executor loopbackExecutor() {
        if (Threading.VIRTUAL.isActive(environment)) {
            return new VirtualThreadTaskExecutor("composite-http-");
        }

        AtomicInteger count = new AtomicInteger();
        int threads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "composite-http-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Executes composite requests.
     *
     * @param endpointRegistry the endpoint registry
     * @param objectMapper the composite object mapper
     * @param activeBatches running batch counter
     * @param validator the request validator
     * @param referenceResolver the reference resolver
     * @param authenticationForwardingService the credential forwarding service
     * @param dispatcher the sub-request dispatcher
     * @param dispatchMarker identifies genuine loopback calls
     * @return the default service
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public CompositeRequestService compositeRequestService(
            EndpointRegistry endpointRegistry,
            @Qualifier("compositeObjectMapper") ObjectMapper objectMapper,
            ActiveBatches activeBatches,
            CompositeRequestValidator validator,
            ReferenceResolverService referenceResolver,
            AuthenticationForwardingService authenticationForwardingService,
            @Lazy SubRequestDispatcher dispatcher,
            DispatchMarker dispatchMarker) {
        return new CompositeRequestServiceImpl(endpointRegistry, objectMapper, activeBatches, validator,
                referenceResolver, properties, authenticationForwardingService, dispatcher, dispatchMarker);
    }

    /**
     * Logs the port and warns when debug mode or trusting every loopback certificate is enabled.
     *
     * {@inheritDoc}
     */
    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() != null) {
            return;
        }
        int serverPort = event.getWebServer().getPort();
        log.info("Composite endpoint library initialized on port {}", serverPort);
        if (properties.isDebugEnabled()) {
            log.warn("Composite debug mode is enabled. Disable in production.");
        }
        LoopbackSupport.warnIfTrustingAllCertificates(environment);
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
        CompositeHealthIndicator compositeHealthIndicator(ActiveBatches activeBatches,
                                                          EndpointRegistry endpointRegistry) {
            return new CompositeHealthIndicator(activeBatches, endpointRegistry);
        }
    }

    /**
     * Fails at startup when the WebClient was requested but WebFlux is missing.
     *
     * @since 0.1.0
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.web.reactive.function.client.WebClient")
    @ConditionalOnProperty(prefix = "composite.loopback", name = "client", havingValue = "webclient")
    static class WebClientUnavailableConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SubRequestDispatcher compositeSubRequestDispatcher() {
            throw new IllegalStateException("composite.loopback.client=webclient requires spring-boot-starter-webflux. "
                    + "Add that dependency, or remove the property to use the built-in JDK client.");
        }
    }

}
