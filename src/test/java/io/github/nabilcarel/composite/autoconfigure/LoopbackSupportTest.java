package io.github.nabilcarel.composite.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void handlerLimit_hasTenPercentHeadroomOverTheConnectionLimit() {
        assertThat(LoopbackSupport.handlerLimit(1)).isEqualTo(2);
        assertThat(LoopbackSupport.handlerLimit(10)).isEqualTo(11);
        assertThat(LoopbackSupport.handlerLimit(100)).isEqualTo(110);
        assertThat(LoopbackSupport.handlerLimit(4096)).isEqualTo(4505);
    }

    @Test
    void deprecatedTrustOption_isSilentWhenUnset(CapturedOutput output) {
        assertThat(LoopbackSupport.warnIfDeprecatedTrustOption(new MockEnvironment())).isFalse();
        assertThat(output).doesNotContain("trust-self-signed-certificates");
    }

    @Test
    void deprecatedTrustOption_warnsThatItHasNoEffect(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("composite.loopback.trustSelfSignedCertificates", "true");

        assertThat(LoopbackSupport.warnIfDeprecatedTrustOption(environment)).isTrue();
        assertThat(output).contains("WARN").contains("deprecated and has no effect");
    }

    @Test
    void plainHttp_pinsNothing() {
        assertThat(LoopbackSupport.loopbackTrust(properties, null, null)).isNull();
    }

    @Test
    void httpsWithoutServerSsl_fallsBackToTheJvmTruststore(CapturedOutput output) {
        properties.setProtocol("https");

        assertThat(LoopbackSupport.loopbackTrust(properties, null, null)).isNull();
        assertThat(output).contains("WARN").contains("server.ssl is not configured");
    }

    @Test
    void namedBundleWithoutBundles_failsClearly() {
        properties.setProtocol("https");
        properties.setSslBundle("loopback");

        assertThatThrownBy(() -> LoopbackSupport.loopbackTrust(properties, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("composite.loopback.ssl-bundle");
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
