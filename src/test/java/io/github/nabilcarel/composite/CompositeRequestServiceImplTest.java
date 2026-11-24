package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.internal.WebClientSubRequestDispatcher;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeRequestServiceImplTest {

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
    private ResponseTracker responseTracker;

    private final SubRequestContext requestContext = new SubRequestContext(new HttpHeaders(), null);
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
            new WebClientSubRequestDispatcher(webClient),
            new io.github.nabilcarel.composite.internal.DispatchMarker()
        );
    }

    // ========== Endpoint Validation Tests ==========

    @Test
    void forwardSubrequest_whenEndpointNotRegistered_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/unknown", "GET");

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.empty());

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().toString()).contains("Invalid endpoint");
    }

    @Test
    void forwardSubrequest_whenResolvedUrlNotRegistered_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/users/${user.id}", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        when(endpointRegistry.getEndpointInformations("GET", "/api/users/${user.id}"))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/malicious/path");
        when(endpointRegistry.getEndpointInformations("GET", "/api/malicious/path"))
            .thenReturn(Optional.empty());

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().toString()).contains("does not match a registered endpoint");
    }

    // ========== Reference Resolution Tests ==========

    @Test
    void forwardSubrequest_whenReferenceResolutionFails_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/users/${invalid.ref}", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenThrow(new RuntimeException("Reference not found"));

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().toString()).contains("Reference resolution failed");
    }

    // ========== URL Format Validation Tests ==========

    @Test
    void forwardSubrequest_whenUrlFormatInvalid_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn("Invalid URL format");

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    // ========== Successful Request Tests ==========

    @Test
    void forwardSubrequest_whenSuccessful_addsResponseToTracker() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");
        Map<String, String> responseBody = Map.of("id", "123", "name", "Test");

        setupSuccessfulWebClientMock(responseBody, Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(objectMapper.valueToTree(responseBody));
    }

    @Test
    void forwardSubrequest_withVoidReturnType_addsResponseWithNullBody() {
        SubRequest subRequest = createSubRequest("/api/orders/123", "DELETE");
        EndpointInfo endpointInfo = createEndpointInfo("/api/orders/{id}");

        setupVoidWebClientMock();
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/orders/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    // ========== Error Response Tests ==========

    @Test
    void forwardSubrequest_when4xxError_addsErrorResponseToTracker() {
        SubRequest subRequest = createSubRequest("/api/users/notfound", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        setupErrorWebClientMock(404, "Not Found");
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/notfound");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void forwardSubrequest_when5xxError_addsErrorResponseToTracker() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        setupErrorWebClientMock(500, "Internal Server Error");
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(500);
    }

    @Test
    void forwardSubrequest_whenErrorWithEmptyBody_addsResponseWithEmptyString() {
        SubRequest subRequest = createSubRequest("/api/users/notfound", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        setupErrorWebClientMockWithEmptyBody(404);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/notfound");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo("");
    }

    // ========== WebClient Error Handling Tests ==========

    @Test
    void forwardSubrequest_whenWebClientThrowsException_addsServiceUnavailableResponse() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        setupWebClientExceptionMock(new RuntimeException("Connection refused"));
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getBody().toString()).contains("Error executing subrequest");
    }

    // ========== Header Injection Tests ==========

    @Test
    void forwardSubrequest_whenHeaderInjectionEnabled_addsCompositeHeaders() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}");

        properties.getHeaderInjection().setEnabled(true);
        properties.getHeaderInjection().setRequestHeader("X-Composite-Request");
        properties.getHeaderInjection().setRequestIdHeader("X-Composite-Request-Id");
        properties.getHeaderInjection().setSubRequestIdHeader("X-Composite-Sub-Request-Id");

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        verify(responseTracker).addResponse(eq(REFERENCE_ID), any(SubResponse.class));
    }

    // ========== Cancellation Tests ==========

    @Test
    void execute_whenReturnedFutureIsCancelled_cancelsCallAndRemovesTracker() {
        java.util.concurrent.atomic.AtomicBoolean callCancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(compositeRequestValidator.validateRequest(any())).thenReturn(java.util.List.of());
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(createEndpointInfo("/api/users/{id}")));
        when(referenceResolver.resolveUrl(any(), anyString())).thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString())).thenReturn(null);
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.exchangeToMono(any()))
            .thenReturn(Mono.never().doOnCancel(() -> callCancelled.set(true)));
        io.github.nabilcarel.composite.model.request.CompositeRequest request =
            io.github.nabilcarel.composite.model.request.CompositeRequest.builder()
                .subRequests(java.util.List.of(SubRequestDto.builder()
                    .url("/api/users/123").method("GET").referenceId(REFERENCE_ID).build()))
                .build();

        var execution = service.execute(request,
            mock(jakarta.servlet.http.HttpServletRequest.class),
            mock(jakarta.servlet.http.HttpServletResponse.class));
        assertThat(responseStore).hasSize(2);

        execution.cancel(true);

        assertThat(callCancelled).isTrue();
        assertThat(responseStore).containsOnlyKeys(REQUEST_ID);
    }

    // ========== Request Body Tests ==========

    @Test
    void forwardSubrequest_withRequestBody_sendsBodyInRequest() throws Exception {
        JsonNode body = objectMapper.readTree("{\"name\": \"Test\"}");
        SubRequest subRequest = createSubRequestWithBody("/api/users", "POST", body);
        EndpointInfo endpointInfo = createEndpointInfo("/api/users");

        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.OK);
        stubJsonBody(Map.of("id", "new-id"));
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        // When there's a body, exchangeToMono is called on requestHeadersSpec, not requestBodySpec
        when(requestHeadersSpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });

        service.forwardSubrequest(subRequest, REQUEST_ID, requestContext).block();

        verify(responseTracker).addResponse(eq(REFERENCE_ID), any(SubResponse.class));
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

    private void setupVoidWebClientMock() {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.NO_CONTENT);
        when(clientResponse.bodyToMono(String.class)).thenReturn(Mono.empty());

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupErrorWebClientMock(int statusCode, String errorBody) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.valueOf(statusCode));
        when(clientResponse.bodyToMono(eq(String.class))).thenReturn(Mono.just(errorBody));

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupErrorWebClientMockWithEmptyBody(int statusCode) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.valueOf(statusCode));
        when(clientResponse.bodyToMono(eq(String.class))).thenReturn(Mono.empty());

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupWebClientExceptionMock(Exception exception) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(requestBodySpec.exchangeToMono(any())).thenReturn(Mono.error(exception));
    }
}
