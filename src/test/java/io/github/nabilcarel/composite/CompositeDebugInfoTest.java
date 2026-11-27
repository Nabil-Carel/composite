package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.internal.ActiveBatches;
import io.github.nabilcarel.composite.internal.BatchExecution;
import io.github.nabilcarel.composite.internal.WebClientSubRequestDispatcher;
import io.github.nabilcarel.composite.model.response.CompositeDebugInfo;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    private ClientResponse.Headers clientResponseHeaders;
    @Mock
    private BatchExecution batch;
    @Mock
    private HttpServletRequest servletRequest;

    private ObjectMapper objectMapper;
    private CompositeProperties properties;
    private ActiveBatches activeBatches;
    private CompositeRequestServiceImpl service;

    private static final String REQUEST_ID = "test-request-id";
    private static final String REFERENCE_ID = "test-ref";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new CompositeProperties();
        properties.setRequestTimeout(Duration.ofSeconds(30));
        activeBatches = new ActiveBatches();

        service = new CompositeRequestServiceImpl(
            endpointRegistry,
            objectMapper,
            activeBatches,
            compositeRequestValidator,
            referenceResolver,
            properties,
            authForwardingService,
            new WebClientSubRequestDispatcher(webClient),
            new io.github.nabilcarel.composite.internal.DispatchMarker()
        );
    }

    private BatchExecution batchWith(SubRequestContext context) {
        lenient().when(batch.id()).thenReturn(REQUEST_ID);
        lenient().when(batch.context()).thenReturn(context);
        lenient().when(batch.responses()).thenReturn(Map.of());
        return batch;
    }

    // ========== Debug Enabled Tests ==========

    @Test
    void forwardSubrequest_whenDebugEnabled_capturesOriginalAndResolvedUrl() {
        properties.setDebugEnabled(true);
        SubRequest subRequest = createSubRequest("/api/users/${dep.id}", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of())
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();
        SubRequestContext requestContext = new SubRequestContext(new HttpHeaders(), debugInfo);

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyMap()))
            .thenReturn("/api/users/42");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, batchWith(requestContext)).block();

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
        EndpointInfo endpointInfo = createEndpointInfo("/api/orders");

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of())
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();
        SubRequestContext requestContext = new SubRequestContext(new HttpHeaders(), debugInfo);

        // Simulate reference resolver modifying the body in-place
        doAnswer(invocation -> {
            SubRequest req = invocation.getArgument(0);
            // Simulate in-place modification (body is a JsonNode, modified by NodeReference)
            ((com.fasterxml.jackson.databind.node.ObjectNode) req.getBody())
                .put("userId", "resolved-42");
            return null;
        }).when(referenceResolver).resolveBody(any(), anyMap());

        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(clientResponse.statusCode()).thenReturn(HttpStatus.OK);
        stubJsonBody(Map.of("orderId", "new-order"));
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyMap()))
            .thenReturn("/api/orders");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });

        service.forwardSubrequest(subRequest, batchWith(requestContext)).block();

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
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of())
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();
        SubRequestContext requestContext = new SubRequestContext(new HttpHeaders(), debugInfo);

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyMap()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, batchWith(requestContext)).block();

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
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyMap()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        CompositeDebugInfo debugInfo = CompositeDebugInfo.builder()
                .dependencyGraph(Map.of())
                .resolvedRequests(new ConcurrentHashMap<>())
                .build();

        service.forwardSubrequest(subRequest, batchWith(new SubRequestContext(new HttpHeaders(), debugInfo))).block();

        assertThat(debugInfo.getResolvedRequests()).isEmpty();
    }

    @Test
    void execute_whenDebugDisabled_responseHasNoDebugField() throws Exception {
        properties.setDebugEnabled(false);
        stubSuccessfulExecution();

        var result = service.execute(compositeRequest(), servletRequest, mock(jakarta.servlet.http.HttpServletResponse.class)).get();

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDebug()).isNull();
    }

    @Test
    void execute_whenDebugEnabled_responseContainsDebugInfo() throws Exception {
        properties.setDebugEnabled(true);
        stubSuccessfulExecution();

        var result = service.execute(compositeRequest(), servletRequest, mock(jakarta.servlet.http.HttpServletResponse.class)).get();

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDebug()).isNotNull();
        assertThat(result.getBody().getDebug().getDependencyGraph()).containsKey(REFERENCE_ID);
        assertThat(result.getBody().getDebug().getResolvedRequests()).containsKey(REFERENCE_ID);
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

    private EndpointInfo createEndpointInfo(String pattern) {
        return EndpointInfo.builder()
            .pattern(pattern)
            .method("GET")
            .build();
    }

    private void stubJsonBody(Object body) {
        when(clientResponse.headers()).thenReturn(clientResponseHeaders);
        when(clientResponseHeaders.contentType()).thenReturn(Optional.of(MediaType.APPLICATION_JSON));
        when(clientResponse.bodyToMono(String.class))
            .thenReturn(Mono.just(objectMapper.valueToTree(body).toString()));
    }

    private <T> void setupSuccessfulWebClientMock(T responseBody, Class<T> responseClass) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.OK);
        stubJsonBody(responseBody);

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private CompositeRequest compositeRequest() {
        return CompositeRequest.builder()
            .subRequests(java.util.List.of(SubRequestDto.builder()
                .url("/api/users/123")
                .method("GET")
                .referenceId(REFERENCE_ID)
                .build()))
            .build();
    }

    private void stubSuccessfulExecution() {
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");
        when(compositeRequestValidator.validateRequest(any())).thenReturn(java.util.List.of());
        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyMap()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);
    }
}
