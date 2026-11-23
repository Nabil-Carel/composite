package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration;
import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CompositeAutoConfigurationContextTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            ValidationAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            CompositeAutoConfiguration.class));

    @Test
    void defaults_areSafe() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CompositeProperties properties = context.getBean(CompositeProperties.class);
            assertThat(properties.getMaxConcurrentSubRequests()).isEqualTo(5);
            assertThat(properties.getSecurity().getForwardedHeaders()).containsExactly("Authorization", "Cookie");
            assertThat(properties.getHeaderInjection().isEnabled()).isTrue();
            assertThat(context.getBean(CompositeLoopbackProperties.class).getResponseTimeout()).isNull();
        });
    }

    @Test
    void invalidConcurrencyLimit_failsAtStartup() {
        runner.withPropertyValues("composite.max-concurrent-sub-requests=0")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidLoopbackConnectionLimit_failsAtStartup() {
        runner.withPropertyValues("composite.loopback.max-connections=0")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void connectionLimit_isLeftUnsetSoItCanFollowTheServerThreadPool() {
        runner.run(context ->
            assertThat(context.getBean(CompositeLoopbackProperties.class).getMaxConnections()).isNull());
    }

    @Test
    void healthIndicator_isRegisteredWithActuator() {
        runner.run(context -> assertThat(context).hasBean("compositeHealthIndicator"));
    }

    @Test
    void healthIndicator_isSkippedWithoutActuator() {
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("compositeHealthIndicator");
            });
    }
}
