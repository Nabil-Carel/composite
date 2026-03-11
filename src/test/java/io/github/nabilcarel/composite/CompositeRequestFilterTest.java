package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.filter.CompositeRequestFilter;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import io.github.nabilcarel.composite.service.CompositeRequestValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeRequestFilterTest {

    @Mock
    private ApplicationContext context;
    @Mock
    private CompositeRequestValidator compositeRequestValidator;
    @Mock
    private CompositeRequestService compositeRequestService;
    @Mock
    private HttpServletRequest servletRequest;
    @Mock
    private HttpServletResponse servletResponse;
    @Mock
    private FilterChain filterChain;

    private ObjectMapper objectMapper;
    private CompositeProperties properties;
    private ConcurrentMap<String, ResponseTracker> responseStore;
    private CompositeRequestFilter filter;

    private static final String VALID_REQUEST_BODY =
            "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/test\"}]}";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new CompositeProperties();
        responseStore = new ConcurrentHashMap<>();
        filter = new CompositeRequestFilter(context, compositeRequestValidator, objectMapper, responseStore, properties);
        when(context.getBean(CompositeRequestService.class)).thenReturn(compositeRequestService);
    }

    @Test
    void doFilter_withValidationErrors_setsErrorAttributes() throws IOException, ServletException {
        setupServletRequest(VALID_REQUEST_BODY);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(List.of("Some error"));

        filter.doFilter(servletRequest, servletResponse, filterChain);

        verify(servletRequest).setAttribute("hasErrors", true);
        verify(servletRequest).setAttribute(eq("errors"), eq(List.of("Some error")));
        assertThat(responseStore).isEmpty();
    }

    @Test
    void doFilter_withNoValidationErrors_storesResponseTracker() throws IOException, ServletException {
        setupServletRequest(VALID_REQUEST_BODY);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(List.of());
        // Needed to prevent NPE from batchContext.startInitialRequests() running inline
        when(compositeRequestService.forwardSubrequest(any(), any(), any())).thenReturn(Mono.empty());

        filter.doFilter(servletRequest, servletResponse, filterChain);

        verify(servletRequest).setAttribute("hasErrors", false);
        verify(servletRequest, never()).setAttribute(eq("errors"), any());
        assertThat(responseStore).hasSize(1);
    }

    @Test
    void doFilter_alwaysSetsRequestIdAndCompositeAttributes() throws IOException, ServletException {
        setupServletRequest(VALID_REQUEST_BODY);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(List.of("error"));

        filter.doFilter(servletRequest, servletResponse, filterChain);

        ArgumentCaptor<String> requestIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(servletRequest).setAttribute(eq("requestId"), requestIdCaptor.capture());
        assertThat(requestIdCaptor.getValue()).isNotNull().isNotEmpty();
        verify(servletRequest).setAttribute("composite", true);
    }

    @Test
    void doFilter_alwaysContinuesFilterChain() throws IOException, ServletException {
        setupServletRequest(VALID_REQUEST_BODY);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(List.of("error"));

        filter.doFilter(servletRequest, servletResponse, filterChain);

        verify(filterChain).doFilter(any(), eq(servletResponse));
    }

    @Test
    void doFilter_lazilyInitializesService() throws IOException, ServletException {
        setupServletRequest(VALID_REQUEST_BODY);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(List.of("error"));

        filter.doFilter(servletRequest, servletResponse, filterChain);

        // Reset for second call
        reset(servletRequest, filterChain);
        setupServletRequest(VALID_REQUEST_BODY);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(List.of("error"));

        filter.doFilter(servletRequest, servletResponse, filterChain);

        verify(context, times(1)).getBean(CompositeRequestService.class);
    }

    // ========== Helper Methods ==========

    private void setupServletRequest(String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        ServletInputStream stream = new ServletInputStream() {
            private final ByteArrayInputStream byteStream = new ByteArrayInputStream(bodyBytes);

            @Override
            public int read() {
                return byteStream.read();
            }

            @Override
            public boolean isFinished() {
                return byteStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {
            }
        };

        when(servletRequest.getInputStream()).thenReturn(stream);
    }
}
