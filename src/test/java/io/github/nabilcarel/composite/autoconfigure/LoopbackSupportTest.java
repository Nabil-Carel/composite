package io.github.nabilcarel.composite.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.springframework.mock.env.MockEnvironment;

class LoopbackSupportTest {

    private final CompositeLoopbackProperties properties = new CompositeLoopbackProperties();

    @Test
    void platformThreads_defaultToHalfTheTomcatThreads() {
        assertThat(LoopbackSupport.effectiveMaxConnections(new MockEnvironment(), properties)).isEqualTo(100);
        assertThat(LoopbackSupport.effectiveMaxConnections(
            new MockEnvironment().withProperty("server.tomcat.threads.max", "50"), properties)).isEqualTo(25);
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void virtualThreads_defaultToHalfTheTomcatConnections() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.threads.virtual.enabled", "true")
            .withProperty("server.tomcat.threads.max", "50");

        assertThat(LoopbackSupport.effectiveMaxConnections(environment, properties)).isEqualTo(4096);
        assertThat(LoopbackSupport.effectiveMaxConnections(
            environment.withProperty("server.tomcat.max-connections", "1000"), properties)).isEqualTo(500);
    }

    @Test
    void explicitLimit_alwaysWins() {
        properties.setMaxConnections(7);

        assertThat(LoopbackSupport.effectiveMaxConnections(new MockEnvironment(), properties)).isEqualTo(7);
        assertThat(LoopbackSupport.effectiveMaxConnections(
            new MockEnvironment().withProperty("spring.threads.virtual.enabled", "true"), properties)).isEqualTo(7);
    }
}
