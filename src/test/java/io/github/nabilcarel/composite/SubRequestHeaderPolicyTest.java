package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.util.SubRequestHeaderPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubRequestHeaderPolicyTest {

    private final CompositeProperties properties = new CompositeProperties();

    @ParameterizedTest
    @ValueSource(strings = {"Accept", "accept-language", "IF-MATCH", "If-None-Match"})
    void defaultAllowlist_acceptsSafeHeadersInAnyCase(String header) {
        assertThat(SubRequestHeaderPolicy.isClientHeaderAllowed(header, properties)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Authorization", "Cookie", "Proxy-Authorization", "Host", "Content-Length", "Transfer-Encoding",
        "Connection", "Upgrade", "Forwarded", "X-Forwarded-For", "X-Forwarded-User", "X-Real-IP",
        "X-Original-URL", "X-HTTP-Method-Override", "X-Composite-Dispatch", "X-Composite-Request-Id"
    })
    void sensitiveHeaders_cannotBeAllowlisted(String header) {
        properties.getSecurity().setClientAllowedHeaders(List.of(header));

        assertThat(SubRequestHeaderPolicy.isClientHeaderAllowed(header, properties)).isFalse();
    }

    @Test
    void unlistedHeader_isRejected() {
        assertThat(SubRequestHeaderPolicy.isClientHeaderAllowed("X-Custom", properties)).isFalse();
        assertThat(SubRequestHeaderPolicy.isClientHeaderAllowed(null, properties)).isFalse();
    }

    @Test
    void configuredBlockedHeader_overridesTheAllowlist() {
        properties.getSecurity().setClientAllowedHeaders(List.of("X-User-Id"));
        properties.getSecurity().setBlockedHeaders(List.of("x-user-id"));

        assertThat(SubRequestHeaderPolicy.isClientHeaderAllowed("X-User-Id", properties)).isFalse();
    }

    @Test
    void forwardedHeader_overridesTheAllowlist() {
        properties.getSecurity().setForwardedHeaders(List.of("Authorization", "X-API-Key"));
        properties.getSecurity().setClientAllowedHeaders(List.of("X-API-Key"));

        assertThat(SubRequestHeaderPolicy.isClientHeaderAllowed("x-api-key", properties)).isFalse();
    }

    @Test
    void customTrackingHeaderNames_areBlocked() {
        properties.getHeaderInjection().setRequestIdHeader("X-Batch-Id");
        properties.getSecurity().setClientAllowedHeaders(List.of("X-Batch-Id"));

        assertThat(SubRequestHeaderPolicy.isClientHeaderAllowed("x-batch-id", properties)).isFalse();
    }
}
