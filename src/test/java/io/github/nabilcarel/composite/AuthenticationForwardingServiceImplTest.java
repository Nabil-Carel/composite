package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.service.AuthenticationForwardingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationForwardingServiceImplTest {

    private MockHttpServletRequest servletRequest;
    private CompositeProperties properties;
    private AuthenticationForwardingServiceImpl service;

    @BeforeEach
    void setUp() {
        servletRequest = new MockHttpServletRequest();
        properties = new CompositeProperties();
        service = new AuthenticationForwardingServiceImpl(properties);
    }

    @Test
    void forwardAuthentication_withConfiguredHeaders_forwardsMatchingHeaders() {
        properties.getSecurity().setForwardedHeaders(List.of("Authorization", "X-API-Key"));
        servletRequest.addHeader("Authorization", "Bearer token123");
        servletRequest.addHeader("X-API-Key", "api-key-value");

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders.getFirst("Authorization")).isEqualTo("Bearer token123");
        assertThat(targetHeaders.getFirst("X-API-Key")).isEqualTo("api-key-value");
    }

    @Test
    void forwardAuthentication_whenHeaderNotPresent_doesNotAddIt() {
        properties.getSecurity().setForwardedHeaders(List.of("Authorization"));

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders.containsKey("Authorization")).isFalse();
    }

    @Test
    void forwardAuthentication_withNoConfiguredHeaders_addsNothing() {
        properties.getSecurity().setForwardedHeaders(List.of());
        servletRequest.addHeader("Authorization", "Bearer token");

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders).isEmpty();
    }

    @Test
    void forwardAuthentication_withMixedPresence_onlyForwardsPresentHeaders() {
        properties.getSecurity().setForwardedHeaders(List.of("Authorization", "Cookie", "X-Custom"));
        servletRequest.addHeader("Authorization", "Bearer token");
        servletRequest.addHeader("X-Custom", "custom-value");

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders.getFirst("Authorization")).isEqualTo("Bearer token");
        assertThat(targetHeaders.containsKey("Cookie")).isFalse();
        assertThat(targetHeaders.getFirst("X-Custom")).isEqualTo("custom-value");
    }

    @Test
    void forwardAuthentication_withRepeatedHeader_forwardsEveryValue() {
        properties.getSecurity().setForwardedHeaders(List.of("X-Custom"));
        servletRequest.addHeader("X-Custom", "first");
        servletRequest.addHeader("X-Custom", "second");

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders.get("X-Custom")).containsExactly("first", "second");
    }
}
