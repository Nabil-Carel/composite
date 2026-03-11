package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.config.filter.CompositeRequestFilter;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.response.CompositeDebugInfo;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.service.*;
import jakarta.servlet.FilterChain;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeDebugInfoTest {

    @Mock
    private EndpointRegistry endpointRegistry;
    @Mock
    private CompositeRequestValidator compositeRequestValidator;
    @Mock
    private ReferenceResolverService referenceResolver;
    @Mock
    private AuthenticationForwardingService authForwardingService;
    @Mock
    private WebClient webClient;
    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private WebClient.RequestBodySpec requestBodySpec;
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private ClientResponse clientResponse;
    @Mock
    private ResponseTracker responseTracker;
    @Mock
    private HttpServletRequest servletRequest;

    private ObjectMapper objectMapper;
    private CompositeProperties properties;
    private ConcurrentMap<String, ResponseTracker> responseStore;
    private CompositeRequestServiceImpl service;

    private static final String REQUEST_ID = "test-request-id";
    private static final String REFERENCE_ID = "test-ref";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new CompositeProperties();
        properties.setRequestTimeout(Duration.ofSeconds(30));
        responseStore = new ConcurrentHashMap<>();
        responseStore.put(REQUEST_ID, responseTracker);

        service = new CompositeRequestServiceImpl(
            endpointRegistry,
            objectMapper,
            responseStore,
            compositeRequestValidator,
            referenceResolver,
            properties,
            authForwardingService,
            webClient
        );
    }

    // ========== Debug Enabled Tests ==========

    @Test
    void forwardSubrequest_whenDebugEnabled_capturesOriginalAndResolvedUrl() {
        properties.setDebugEnabled(true);
        SubRequest subRequest = createSubRequest("/api/users/${dep.id}", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of())
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();
        when(servletRequest.getAttribute("compositeDebug")).thenReturn(debugInfo);

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/42");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        assertThat(debugInfo.getResolvedRequests()).containsKey(REFERENCE_ID);
        CompositeDebugInfo.SubRequestDebugInfo subDebug = debugInfo.getResolvedRequests().get(REFERENCE_ID);
        assertThat(subDebug.getOriginalUrl()).isEqualTo("/api/users/${dep.id}");
        assertThat(subDebug.getResolvedUrl()).isEqualTo("/api/users/42");
    }

    @Test
    void forwardSubrequest_whenDebugEnabled_capturesOriginalAndResolvedBody() throws Exception {
        properties.setDebugEnabled(true);
        JsonNode body = objectMapper.readTree("{\"userId\": \"${dep.id}\"}");
        SubRequest subRequest = createSubRequestWithBody("/api/orders", "POST", body);
        EndpointInfo endpointInfo = createEndpointInfo("/api/orders", Map.class);

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of())
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();
        when(servletRequest.getAttribute("compositeDebug")).thenReturn(debugInfo);

        // Simulate reference resolver modifying the body in-place
        doAnswer(invocation -> {
            SubRequest req = invocation.getArgument(0);
            // Simulate in-place modification (body is a JsonNode, modified by NodeReference)
            ((com.fasterxml.jackson.databind.node.ObjectNode) req.getBody())
                .put("userId", "resolved-42");
            return null;
        }).when(referenceResolver).resolveBody(any(), anyString());

        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(clientResponse.statusCode()).thenReturn(HttpStatus.OK);
        when(clientResponse.bodyToMono(eq(Map.class))).thenReturn(Mono.just(Map.of("orderId", "new-order")));
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/orders");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        assertThat(debugInfo.getResolvedRequests()).containsKey(REFERENCE_ID);
        CompositeDebugInfo.SubRequestDebugInfo subDebug = debugInfo.getResolvedRequests().get(REFERENCE_ID);
        // Original body was snapshot before resolution
        assertThat(subDebug.getOriginalBody().toString()).contains("${dep.id}");
        // Resolved body reflects in-place modifications
        assertThat(subDebug.getResolvedBody().toString()).contains("resolved-42");
    }

    @Test
    void forwardSubrequest_whenDebugEnabled_doesNotIncludeHeaders() {
        properties.setDebugEnabled(true);
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        subRequest.getSubRequestDto().getHeaders().put("Authorization", "Bearer secret-token");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of())
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();
        when(servletRequest.getAttribute("compositeDebug")).thenReturn(debugInfo);

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        assertThat(debugInfo.getResolvedRequests()).containsKey(REFERENCE_ID);
        CompositeDebugInfo.SubRequestDebugInfo subDebug = debugInfo.getResolvedRequests().get(REFERENCE_ID);
        // SubRequestDebugInfo has no header fields — headers are excluded by design
        assertThat(subDebug).hasNoNullFieldsOrPropertiesExcept("originalBody", "resolvedBody");
    }

    // ========== Debug Disabled Tests ==========

    @Test
    void forwardSubrequest_whenDebugDisabled_doesNotCaptureDebugInfo() {
        properties.setDebugEnabled(false);
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        // Should never attempt to get the debug attribute
        verify(servletRequest, never()).getAttribute("compositeDebug");
    }

    @Test
    void execute_whenDebugDisabled_responseHasNoDebugField() throws Exception {
        properties.setDebugEnabled(false);

        when(servletRequest.getAttribute("requestId")).thenReturn(REQUEST_ID);
        when(servletRequest.getAttribute("hasErrors")).thenReturn(false);

        CompositeResponse compositeResponse = CompositeResponse.builder()
                .responses(Map.of("ref1", SubResponse.builder().referenceId("ref1").httpStatus(200).build()))
                .hasErrors(false)
                .build();
        when(responseTracker.getFuture())
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(compositeResponse));

        var result = service.execute(servletRequest, mock(HttpServletResponse.class)).get();

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDebug()).isNull();
    }

    @Test
    void execute_whenDebugEnabled_responseContainsDebugInfo() throws Exception {
        properties.setDebugEnabled(true);

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of("ref1", java.util.Set.of()))
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();

        when(servletRequest.getAttribute("requestId")).thenReturn(REQUEST_ID);
        when(servletRequest.getAttribute("hasErrors")).thenReturn(false);
        when(servletRequest.getAttribute("compositeDebug")).thenReturn(debugInfo);

        CompositeResponse compositeResponse = CompositeResponse.builder()
                .responses(Map.of("ref1", SubResponse.builder().referenceId("ref1").httpStatus(200).build()))
                .hasErrors(false)
                .build();
        when(responseTracker.getFuture())
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(compositeResponse));

        var result = service.execute(servletRequest, mock(HttpServletResponse.class)).get();

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDebug()).isNotNull();
        assertThat(result.getBody().getDebug().getDependencyGraph()).containsKey("ref1");
    }

    // ========== Filter Debug Tests ==========

    @Test
    void filter_whenDebugEnabled_setsDebugAttributeOnRequest() throws Exception {
        CompositeProperties filterProperties = new CompositeProperties();
        filterProperties.setDebugEnabled(true);

        ApplicationContext context = mock(ApplicationContext.class);
        CompositeRequestService compositeRequestService = mock(CompositeRequestService.class);
        when(context.getBean(CompositeRequestService.class)).thenReturn(compositeRequestService);
        when(compositeRequestService.forwardSubrequest(any(), any(), any())).thenReturn(Mono.empty());

        ConcurrentMap<String, ResponseTracker> store = new ConcurrentHashMap<>();
        CompositeRequestFilter filter = new CompositeRequestFilter(
            context,
            compositeRequestValidator,
            objectMapper,
            store,
            filterProperties
        );

        String body = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/test\"}]}";
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        setupServletRequest(mockRequest, body);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(java.util.List.of());

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(mockRequest, mock(HttpServletResponse.class), chain);

        ArgumentCaptor<CompositeDebugInfo> debugCaptor = ArgumentCaptor.forClass(CompositeDebugInfo.class);
        verify(mockRequest).setAttribute(eq("compositeDebug"), debugCaptor.capture());

        CompositeDebugInfo captured = debugCaptor.getValue();
        assertThat(captured).isNotNull();
        assertThat(captured.getDependencyGraph()).containsKey("a");
        assertThat(captured.getResolvedRequests()).isEmpty();
    }

    @Test
    void filter_whenDebugDisabled_doesNotSetDebugAttribute() throws Exception {
        CompositeProperties filterProperties = new CompositeProperties();
        filterProperties.setDebugEnabled(false);

        ApplicationContext context = mock(ApplicationContext.class);
        CompositeRequestService compositeRequestService = mock(CompositeRequestService.class);
        when(context.getBean(CompositeRequestService.class)).thenReturn(compositeRequestService);
        when(compositeRequestService.forwardSubrequest(any(), any(), any())).thenReturn(Mono.empty());

        ConcurrentMap<String, ResponseTracker> store = new ConcurrentHashMap<>();
        CompositeRequestFilter filter = new CompositeRequestFilter(
            context,
            compositeRequestValidator,
            objectMapper,
            store,
            filterProperties
        );

        String body = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/test\"}]}";
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        setupServletRequest(mockRequest, body);
        when(compositeRequestValidator.validateRequest(any())).thenReturn(java.util.List.of());

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(mockRequest, mock(HttpServletResponse.class), chain);

        verify(mockRequest, never()).setAttribute(eq("compositeDebug"), any());
    }

    // ========== JSON Serialization Test ==========

    @Test
    void compositeResponse_debugFieldOmittedFromJsonWhenNull() throws Exception {
        CompositeResponse response = CompositeResponse.builder()
                .responses(Map.of())
                .hasErrors(false)
                .build();

        String json = objectMapper.writeValueAsString(response);
        assertThat(json).doesNotContain("debug");
    }

    @Test
    void compositeResponse_debugFieldPresentInJsonWhenSet() throws Exception {
        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of("a", java.util.Set.of("b")))
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();

        CompositeResponse response = CompositeResponse.builder()
                .responses(Map.of())
                .hasErrors(false)
                .debug(debugInfo)
                .build();

        String json = objectMapper.writeValueAsString(response);
        assertThat(json).contains("debug");
        assertThat(json).contains("dependencyGraph");
    }

    // ========== Helper Methods ==========

    private SubRequest createSubRequest(String url, String method) {
        SubRequestDto dto = SubRequestDto.builder()
            .url(url)
            .method(method)
            .referenceId(REFERENCE_ID)
            .build();
        SubRequest subRequest = new SubRequest(dto);
        subRequest.setHeaders(new HashMap<>());
        return subRequest;
    }

    private SubRequest createSubRequestWithBody(String url, String method, JsonNode body) {
        SubRequestDto dto = SubRequestDto.builder()
            .url(url)
            .method(method)
            .referenceId(REFERENCE_ID)
            .body(body)
            .build();
        SubRequest subRequest = new SubRequest(dto);
        subRequest.setHeaders(new HashMap<>());
        return subRequest;
    }

    private EndpointInfo createEndpointInfo(String pattern, Class<?> returnClass) {
        return EndpointInfo.builder()
            .pattern(pattern)
            .method("GET")
            .returnClass(returnClass)
            .build();
    }

    private <T> void setupSuccessfulWebClientMock(T responseBody, Class<T> responseClass) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.OK);
        when(clientResponse.bodyToMono(eq(responseClass))).thenReturn(Mono.just(responseBody));

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupServletRequest(HttpServletRequest request, String body) throws IOException {
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

        when(request.getInputStream()).thenReturn(stream);
    }
}
