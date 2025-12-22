package io.github.nabilcarel.composite.autoconfigure;

import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.filter.CompositeRequestFilter;
import io.github.nabilcarel.composite.controller.CompositeController;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.service.*;
import io.netty.channel.ChannelOption;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private int serverPort;


    @Bean("compositeObjectMapper")
    @ConditionalOnMissingBean(name = "compositeObjectMapper")
    public ObjectMapper objectMapper(){
        return new ObjectMapper();
    }

    @Bean
    public FilterRegistrationBean<CompositeRequestFilter> compositeFilter(CompositeRequestFilter filter) {
        FilterRegistrationBean<CompositeRequestFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(filter);
        registrationBean.addUrlPatterns(properties.getFilterPattern());
        registrationBean.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return registrationBean;
    }

    @Bean
    public ConcurrentMap<String, ResponseTracker> responseStore(){
        return new ConcurrentHashMap<>();
    }

    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
        log.info("Composite endpoint library initialized on port {}", serverPort);
    }

    @Bean("compositeWebClient")
    @Lazy
    @ConditionalOnMissingBean(name = "compositeWebClient")
    public WebClient loopbackWebClient(CompositeLoopbackProperties properties){
        String baseUrl = String.format("%s://localhost:%d", properties.getProtocol(), serverPort);

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,(int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getResponseTimeout());

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
