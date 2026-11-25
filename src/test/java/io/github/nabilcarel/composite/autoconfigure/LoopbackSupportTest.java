package io.github.nabilcarel.composite.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class LoopbackSupportTest {

    private final CompositeLoopbackProperties properties = new CompositeLoopbackProperties();

    @Test
    void trustingAllCertificates_isOffAndSilentByDefault(CapturedOutput output) {
        assertThat(LoopbackSupport.warnIfTrustingAllCertificates(new MockEnvironment())).isFalse();
        assertThat(output).doesNotContain("trust-self-signed-certificates");
    }

    @Test
    void trustingAllCertificates_overHttps_warnsAtStartup(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("composite.loopback.protocol", "https")
            .withProperty("composite.loopback.trust-self-signed-certificates", "true");

        assertThat(LoopbackSupport.warnIfTrustingAllCertificates(environment)).isTrue();
        assertThat(output).contains("WARN").contains("accept any certificate")
            .contains("development and tests only");
    }

    @Test
    void trustingAllCertificates_overHttp_warnsThatItHasNoEffect(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("composite.loopback.trustSelfSignedCertificates", "true");

        assertThat(LoopbackSupport.warnIfTrustingAllCertificates(environment)).isTrue();
        assertThat(output).contains("has no effect");
    }

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
