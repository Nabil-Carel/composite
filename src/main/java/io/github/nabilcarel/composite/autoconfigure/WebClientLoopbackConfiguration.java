package io.github.nabilcarel.composite.autoconfigure;

import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import io.github.nabilcarel.composite.internal.LoopbackAddress;
import io.github.nabilcarel.composite.internal.WebClientSubRequestDispatcher;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriBuilderFactory;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Sends sub-requests with Reactor Netty's {@link WebClient}.
 *
 * <p>Active only with {@code composite.loopback.client=webclient} and
 * {@code spring-boot-starter-webflux} on the classpath. Every WebFlux and Netty type the library
 * uses is confined to this class, so applications using the default JDK client do not need them.
 *
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
@ConditionalOnProperty(prefix = "composite.loopback", name = "client", havingValue = "webclient")
class WebClientLoopbackConfiguration {

    /**
     * Provides the loopback connection pool, released when the context closes.
     *
     * @param loopbackProperties loopback limits
     * @param properties composite settings
     * @param environment the environment
     * @return the connection pool
     */
    @Bean(destroyMethod = "dispose")
    @Lazy
    ConnectionProvider compositeConnectionProvider(CompositeLoopbackProperties loopbackProperties,
                                                   CompositeProperties properties, Environment environment) {
        return ConnectionProvider.builder("composite-loopback")
                .maxConnections(LoopbackSupport.effectiveMaxConnections(environment, loopbackProperties))
                .pendingAcquireMaxCount(-1)
                .pendingAcquireTimeout(properties.getRequestTimeout())
                .build();
    }

    /**
     * Provides the loopback {@link WebClient}. Declare a bean named {@code compositeWebClient}
     * to replace it.
     *
     * @param loopbackProperties loopback configuration (protocol, timeouts, SSL)
     * @param address the application's base URL
     * @param connectionProvider the loopback connection pool
     * @param environment the environment
     * @return a client targeting the application's local URL
     */
    @Bean("compositeWebClient")
    @Lazy
    @ConditionalOnMissingBean(name = "compositeWebClient")
    WebClient loopbackWebClient(CompositeLoopbackProperties loopbackProperties, LoopbackAddress address,
                                @Lazy ConnectionProvider connectionProvider, Environment environment) {
        int maxResponseSize = LoopbackSupport.toBufferLimit(loopbackProperties.getMaxResponseSize());

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) loopbackProperties.getConnectTimeout().toMillis());

        if (loopbackProperties.getResponseTimeout() != null) {
            httpClient = httpClient.responseTimeout(loopbackProperties.getResponseTimeout());
        }

        if (LoopbackSupport.isHttps(loopbackProperties)) {
            LoopbackSupport.logHttpsSetup(environment, loopbackProperties);
            if (loopbackProperties.isTrustSelfSignedCertificates()) {
                httpClient = trustAllCertificates(httpClient);
            }
        }

        return WebClient.builder()
                // A custom controller can create this client before the server has chosen its port.
                .uriBuilderFactory(new LoopbackUriBuilderFactory(address::baseUrl))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseSize))
                .build();
    }

    /**
     * Sends sub-requests through the loopback {@link WebClient}.
     *
     * @param webClient the lazily created loopback client
     * @return the dispatcher
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    SubRequestDispatcher compositeSubRequestDispatcher(@Lazy @Qualifier("compositeWebClient") WebClient webClient) {
        return new WebClientSubRequestDispatcher(webClient);
    }

    private static HttpClient trustAllCertificates(HttpClient httpClient) {
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            return httpClient.secure(spec -> spec.sslContext(sslContext));
        } catch (SSLException e) {
            throw new IllegalStateException("Failed to configure SSL context for self-signed certificates", e);
        }
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
}
