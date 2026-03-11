package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.service.AuthenticationForwardingServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationForwardingServiceImplTest {

    @Mock
    private HttpServletRequest servletRequest;

    private CompositeProperties properties;
    private AuthenticationForwardingServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new CompositeProperties();
        service = new AuthenticationForwardingServiceImpl(properties);
    }

    @Test
    void forwardAuthentication_withConfiguredHeaders_forwardsMatchingHeaders() {
        properties.getSecurity().setForwardedHeaders(List.of("Authorization", "X-API-Key"));
        when(servletRequest.getHeader("Authorization")).thenReturn("Bearer token123");
        when(servletRequest.getHeader("X-API-Key")).thenReturn("api-key-value");

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders.getFirst("Authorization")).isEqualTo("Bearer token123");
        assertThat(targetHeaders.getFirst("X-API-Key")).isEqualTo("api-key-value");
    }

    @Test
    void forwardAuthentication_whenHeaderNotPresent_doesNotAddIt() {
        properties.getSecurity().setForwardedHeaders(List.of("Authorization"));
        when(servletRequest.getHeader("Authorization")).thenReturn(null);

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders.containsKey("Authorization")).isFalse();
    }

    @Test
    void forwardAuthentication_withNoConfiguredHeaders_addsNothing() {
        properties.getSecurity().setForwardedHeaders(List.of());

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders).isEmpty();
    }

    @Test
    void forwardAuthentication_withMixedPresence_onlyForwardsPresentHeaders() {
        properties.getSecurity().setForwardedHeaders(List.of("Authorization", "Cookie", "X-Custom"));
        when(servletRequest.getHeader("Authorization")).thenReturn("Bearer token");
        when(servletRequest.getHeader("Cookie")).thenReturn(null);
        when(servletRequest.getHeader("X-Custom")).thenReturn("custom-value");

        HttpHeaders targetHeaders = new HttpHeaders();
        service.forwardAuthentication(servletRequest, targetHeaders);

        assertThat(targetHeaders.getFirst("Authorization")).isEqualTo("Bearer token");
        assertThat(targetHeaders.containsKey("Cookie")).isFalse();
        assertThat(targetHeaders.getFirst("X-Custom")).isEqualTo("custom-value");
    }
}
